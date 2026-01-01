package domain.mapping;

import java.lang.reflect.Field;

import java.lang.reflect.Method;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.*;

import infra.mapping.ColumnMappingXlsxLoader;

/**
 * Column mapping registry loaded from column_mapping.xlsx.
 *
 * Bug fixes:
 * - byAsisColOnly / byTobeColOnly are now "unique only" indexes.
 *   If a column id appears in multiple tables, column-only lookup becomes ambiguous -> returns null.
 * - Empty keys (blank column ids) are not indexed.
 *   This avoids accidental matches when a TOBE column is deleted (blank in XLSX).
 */
public class ColumnMappingRegistry {

    // 원본 매핑: key = ASIS_TABLE.ASIS_COLUMN (UPPER)
    private final Map<String, ColumnMapping> mappingMap;

    // 보조 인덱스(컬럼) - ✅ 유일할 때만 조회 가능
    private final Map<String, ColumnMapping> byAsisColOnlyUnique = new HashMap<>(); // key=ASIS_COL
    private final Set<String> byAsisColAmbiguous = new HashSet<>();

    private final Map<String, ColumnMapping> byTobeColOnlyUnique = new HashMap<>(); // key=TOBE_COL
    private final Set<String> byTobeColAmbiguous = new HashSet<>();

    // key=ASIS_TABLE.TOBE_COL (UPPER)  (컬럼이 이미 TOBE로 바뀐 상황에서 역조회 용)
    private final Map<String, ColumnMapping> byTobeOnAsisTable = new HashMap<>();

    // ✅ 테이블 매핑(현행테이블ID -> (TOBE)테이블ID)
    private final Map<String, String> asisToTobeTableId = new HashMap<>();

    private volatile Set<String> asisTableIdsUpperCache;
    private volatile Set<String> asisColumnIdsUpperCache;

    public ColumnMappingRegistry(String xlsxResourcePath) {

        String resolvedPath = resolveMappingXlsxPath(xlsxResourcePath);

        ColumnMappingXlsxLoader loader = new ColumnMappingXlsxLoader(); // no-arg constructor
        this.mappingMap = loader.load(resolvedPath);                    // load(String)

        // 인덱스 구성
        for (ColumnMapping m : mappingMap.values()) {
            String asisTable = upper(m.asisTableId);
            String asisCol = upper(m.asisColumnId);
            String tobeCol = upper(m.tobeColumnId);

            // ✅ column-only index는 "유일할 때만" 사용 가능
            if (!asisCol.isEmpty()) {
                if (byAsisColAmbiguous.contains(asisCol)) {
                    // already ambiguous
                } else if (!byAsisColOnlyUnique.containsKey(asisCol)) {
                    byAsisColOnlyUnique.put(asisCol, m);
                } else {
                    // duplicate found -> ambiguous
                    byAsisColOnlyUnique.remove(asisCol);
                    byAsisColAmbiguous.add(asisCol);
                }
            }

            if (!tobeCol.isEmpty()) {
                if (byTobeColAmbiguous.contains(tobeCol)) {
                    // already ambiguous
                } else if (!byTobeColOnlyUnique.containsKey(tobeCol)) {
                    byTobeColOnlyUnique.put(tobeCol, m);
                } else {
                    byTobeColOnlyUnique.remove(tobeCol);
                    byTobeColAmbiguous.add(tobeCol);
                }
            }

            // ✅ tobeCol이 비어있으면 (삭제/미정) 역조회 인덱스에 넣지 않는다.
            if (!asisTable.isEmpty() && !tobeCol.isEmpty()) {
                byTobeOnAsisTable.put(asisTable + "." + tobeCol, m);
            }

            // ✅ (현행테이블ID -> (TOBE)테이블ID) 매핑 수집
            // ColumnMapping 필드/Getter 명이 프로젝트마다 다를 수 있어 reflection으로 읽음
            String tobeTable = upper(readAnyString(m,
                    "tobeTableId", "tableId", "tobeTblId", "tobeTable",
                    "TOBE_TABLE_ID", "TABLE_ID"
            ));

            if (!asisTable.isEmpty() && !tobeTable.isEmpty()) {
                asisToTobeTableId.putIfAbsent(asisTable, tobeTable);
            }
        }

        System.out.println("[INIT] Column mapping size = " + (mappingMap == null ? 0 : mappingMap.size()));
        System.out.println("[INIT] Column-only unique index(ASIS) = " + byAsisColOnlyUnique.size()
                + ", ambiguous = " + byAsisColAmbiguous.size());
        System.out.println("[INIT] Column-only unique index(TOBE) = " + byTobeColOnlyUnique.size()
                + ", ambiguous = " + byTobeColAmbiguous.size());
    }

    // ============================================================
    // ✅ Deleted column helpers
    // ============================================================

    /**
     * TOBE 컬럼ID가 비어있으면(엑셀에서 TOBE 컬럼ID/컬럼명 삭제 등) "삭제된 컬럼"으로 간주한다.
     *
     * 주의:
     * - 삭제 처리는 변환 단계에서 SQL을 깨지지 않게 만들기 위한 정책이다.
     * - "미정"과 "삭제"를 구분하고 싶으면 별도 플래그 컬럼을 엑셀에 두는 것이 가장 안전하다.
     */
    public boolean isDeleted(ColumnMapping m) {
        if (m == null) return false;
        String tobe = (m.tobeColumnId == null) ? "" : m.tobeColumnId.trim();
        return tobe.isEmpty();
    }

    /** 삭제 컬럼 라인에 붙일 기본 코멘트(표준 문구) */
    public String deletedComment(String originalColumnIdUpper) {
        String c = (originalColumnIdUpper == null) ? "" : originalColumnIdUpper.trim();
        if (c.isEmpty()) return "삭제된 컬럼";
        return "삭제된 컬럼: " + c;
    }

    /** ✅ 로딩된 컬럼 매핑 개수 */
    public int size() {
        return mappingMap == null ? 0 : mappingMap.size();
    }

    /** 테이블 + 컬럼 기준 (ASIS_TABLE + ASIS_COLUMN) */
    public ColumnMapping find(String table, String column) {
        if (table == null || column == null) return null;
        return mappingMap.get(upper(table) + "." + upper(column));
    }

    /** 컬럼만 기준 (ASIS_COLUMN) - ✅ 유일할 때만 반환 */
    public ColumnMapping findByColumnOnly(String column) {
        if (column == null) return null;
        String key = upper(column);
        if (key.isEmpty()) return null;
        if (byAsisColAmbiguous.contains(key)) return null;
        return byAsisColOnlyUnique.get(key);
    }

    /** ASIS 컬럼ID가 column-only 인덱스에서 모호(여러 테이블 중복)한지 여부 */
    public boolean isAsisColumnAmbiguous(String column) {
        if (column == null) return false;
        String key = upper(column);
        if (key.isEmpty()) return false;
        return byAsisColAmbiguous.contains(key);
    }

    /** 컬럼만 기준 (TOBE_COLUMN) - ✅ 유일할 때만 반환 */
    public ColumnMapping findByTobeColumnOnly(String tobeColumnId) {
        if (tobeColumnId == null) return null;
        String key = upper(tobeColumnId);
        if (key.isEmpty()) return null;
        if (byTobeColAmbiguous.contains(key)) return null;
        return byTobeColOnlyUnique.get(key);
    }

    /** TOBE 컬럼ID가 column-only 인덱스에서 모호(여러 테이블 중복)한지 여부 */
    public boolean isTobeColumnAmbiguous(String tobeColumnId) {
        if (tobeColumnId == null) return false;
        String key = upper(tobeColumnId);
        if (key.isEmpty()) return false;
        return byTobeColAmbiguous.contains(key);
    }

    /**
     * 변환 과정에서 테이블은 아직 ASIS인데,
     * 컬럼은 이미 TOBE로 바뀐 상황을 커버하기 위한 역조회
     * (ASIS_TABLE + TOBE_COLUMN)
     */
    public ColumnMapping findByTobeOnAsisTable(String asisTableId, String tobeColumnId) {
        if (asisTableId == null || tobeColumnId == null) return null;
        String t = upper(asisTableId);
        String c = upper(tobeColumnId);
        if (t.isEmpty() || c.isEmpty()) return null;
        return byTobeOnAsisTable.get(t + "." + c);
    }

    /** ✅ TOBE_SQL 모드에서 테이블 치환용: 현행테이블ID -> (TOBE)테이블ID */
    public String findTobeTableId(String asisTableId) {
        if (asisTableId == null) return null;
        return asisToTobeTableId.get(upper(asisTableId));
    }

    private String upper(String s) {
        return (s == null) ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * ColumnMapping 객체에서 필드명/Getter명이 어떤 형태든 문자열을 최대한 찾아낸다.
     */
    private static String readAnyString(Object obj, String... candidates) {
        if (obj == null || candidates == null) return null;

        Class<?> c = obj.getClass();

        for (String name : candidates) {
            // 1) getter: getXxx()
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                Method m = c.getMethod(getter);
                Object v = m.invoke(obj);
                if (v != null) return String.valueOf(v);
            } catch (Exception ignored) {}

            // 2) field direct
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) return String.valueOf(v);
            } catch (Exception ignored) {}
        }

        return null;
    }

    // ============================================================
    // ✅ jar 위치(baseDir) 기준: column_mapping.xlsx 경로 해석
    // ============================================================

    private static String resolveMappingXlsxPath(String raw) {

        Path baseDir = resolveBaseDir();

        // 값이 없으면 기본 후보를 순서대로 탐색
        if (raw == null || raw.isBlank()) {
            Path p1 = baseDir.resolve("column_mapping.xlsx");
            if (Files.exists(p1)) return p1.toAbsolutePath().normalize().toString();

            Path p2 = baseDir.resolve("mapping").resolve("column_mapping.xlsx");
            if (Files.exists(p2)) return p2.toAbsolutePath().normalize().toString();

            return p2.toAbsolutePath().normalize().toString();
        }

        // 값이 있으면: 상대경로는 baseDir 기준
        Path p = Path.of(raw.trim());
        if (!p.isAbsolute()) p = baseDir.resolve(p);
        p = p.toAbsolutePath().normalize();

        // 지정 경로가 없으면 baseDir 루트 파일도 한 번 더 탐색 (호환성)
        if (!Files.exists(p)) {
            Path alt = baseDir.resolve("column_mapping.xlsx");
            if (Files.exists(alt)) return alt.toAbsolutePath().normalize().toString();
        }

        return p.toString();
    }

    private static Path resolveBaseDir() {

        // 1) explicit override
        String baseDir = System.getProperty("baseDir");
        if (baseDir != null && !baseDir.isBlank()) {
            try {
                return Path.of(baseDir).toAbsolutePath().normalize();
            } catch (Exception ignored) {
            }
        }

        // 2) jar location (when running as jar / library)
        try {
            var cs = ColumnMappingRegistry.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path codePath = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
                String lower = codePath.toString().toLowerCase(Locale.ROOT);

                if (Files.isRegularFile(codePath) && lower.endsWith(".jar")) {
                    Path parent = codePath.getParent();
                    if (parent != null) return parent.toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {
        }

        // 3) fallback
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /**
     * Test-friendly constructor (no XLSX I/O).
     * Builds indexes from the provided mapping map.
     */
    public ColumnMappingRegistry(Map<String, ColumnMapping> mappingMap, Map<String, String> asisToTobeTableIdForTest) {
        this.mappingMap = mappingMap == null ? Map.of() : mappingMap;
        if (asisToTobeTableIdForTest != null) {
            this.asisToTobeTableId.putAll(asisToTobeTableIdForTest);
        }

        // rebuild indexes (same rules as main constructor)
        for (ColumnMapping m : this.mappingMap.values()) {
            String asisTable = upper(m.asisTableId);
            String asisCol = upper(m.asisColumnId);
            String tobeCol = upper(m.tobeColumnId);

            if (!asisCol.isEmpty()) {
                if (byAsisColAmbiguous.contains(asisCol)) {
                    // already ambiguous
                } else if (!byAsisColOnlyUnique.containsKey(asisCol)) {
                    byAsisColOnlyUnique.put(asisCol, m);
                } else {
                    byAsisColOnlyUnique.remove(asisCol);
                    byAsisColAmbiguous.add(asisCol);
                }
            }

            if (!tobeCol.isEmpty()) {
                if (byTobeColAmbiguous.contains(tobeCol)) {
                    // already ambiguous
                } else if (!byTobeColOnlyUnique.containsKey(tobeCol)) {
                    byTobeColOnlyUnique.put(tobeCol, m);
                } else {
                    byTobeColOnlyUnique.remove(tobeCol);
                    byTobeColAmbiguous.add(tobeCol);
                }
            }

            if (!asisTable.isEmpty() && !tobeCol.isEmpty()) {
                byTobeOnAsisTable.put(asisTable + "." + tobeCol, m);
            }
        }
    }


    /**
     * Upper-cased set of AS-IS table ids present in the loaded mapping.
     * <p>Used by AS-IS residue validation.</p>
     */
    public Set<String> getAsisTableIdsUpper() {
        Set<String> cached = asisTableIdsUpperCache;
        if (cached != null) return cached;

        Set<String> s = new HashSet<>(Math.max(64, mappingMap.size() / 2));
        for (ColumnMapping m : mappingMap.values()) {
            if (m == null) continue;
            String t = safeUpper(m.asisTableId);
            if (!t.isEmpty()) s.add(t);
        }
        cached = Collections.unmodifiableSet(s);
        asisTableIdsUpperCache = cached;
        return cached;
    }

    /**
     * Upper-cased set of AS-IS column ids present in the loaded mapping.
     * <p>Used by AS-IS residue validation.</p>
     */
    public Set<String> getAsisColumnIdsUpper() {
        Set<String> cached = asisColumnIdsUpperCache;
        if (cached != null) return cached;

        Set<String> s = new HashSet<>(Math.max(128, mappingMap.size()));
        for (ColumnMapping m : mappingMap.values()) {
            if (m == null) continue;
            String c = safeUpper(m.asisColumnId);
            if (!c.isEmpty()) s.add(c);
        }
        cached = Collections.unmodifiableSet(s);
        asisColumnIdsUpperCache = cached;
        return cached;
    }

    private static String safeUpper(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        return t.toUpperCase(Locale.ROOT);
    }

}
