package infra.mapping;

import org.apache.poi.ss.usermodel.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;

import java.net.URI;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.HashMap;

import java.util.Locale;

import java.util.Map;

import domain.mapping.ColumnMapping;

public class ColumnMappingXlsxLoader {

    /**
     * key = ASIS_TABLE_ID.ASIS_COLUMN_ID (대문자)
     */
    public Map<String, ColumnMapping> load(String resourcePath) {
        try (InputStream is = openStream(resourcePath)) {

            Map<String, ColumnMapping> result = new HashMap<>();

            try (Workbook wb = new XSSFWorkbook(is)) {

                Sheet sheet = wb.getSheetAt(0);
                int rowIdx = 0;

                for (Row row : sheet) {

                    // 헤더 스킵
                    if (rowIdx++ == 0) continue;

                    if (row.getCell(1) == null || row.getCell(3) == null) continue;

                    ColumnMapping mapping = new ColumnMapping(
                            get(row, 0), // 주제영역
                            get(row, 1).toUpperCase(), // 현행테이블ID
                            get(row, 2),               // 현행테이블명
                            get(row, 3).toUpperCase(), // 현행컬럼ID
                            get(row, 4),               // 현행컬럼명
                            get(row, 5).toUpperCase(), // TO-BE 테이블ID
                            get(row, 6),               // TO-BE 테이블명
                            get(row, 7),               // TO-BE 컬럼ID
                            get(row, 8)                // TO-BE 컬럼명
                    );

                    result.put(
                            mapping.asisTableId + "." + mapping.asisColumnId,
                            mapping
                    );
                }

                System.out.println("[INIT] Column mapping size = " + result.size());
                return result;

            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to load column mapping XLSX", e
                );
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to open column mapping XLSX", e);
        }
    }

    /**
     * 1) file:/... URI 또는 파일시스템 경로가 존재하면 그걸 사용
     * 2) ✅ 상대경로면 baseDir(=jar 위치)/상대경로 를 우선 탐색
     * 3) 아니면 classpath 리소스로 로딩
     */
    private InputStream openStream(String location) throws Exception {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location is blank");
        }

        String loc = location.trim();

        if (loc.startsWith("file:")) {
            Path p = Path.of(URI.create(loc));
            if (!Files.exists(p)) {
                throw new IllegalStateException("File not found: " + p);
            }
            return Files.newInputStream(p);
        }

        Path p = Path.of(loc);

        // ✅ 상대경로면 baseDir 기준으로 먼저 찾는다
        if (!p.isAbsolute()) {
            Path baseDir = resolveBaseDir();
            Path pb = baseDir.resolve(p).toAbsolutePath().normalize();
            if (Files.exists(pb)) {
                return Files.newInputStream(pb);
            }

            // 호환: mapping/column_mapping.xlsx 로 왔지만 baseDir 루트에 파일이 있는 경우도 커버
            String fn = p.getFileName() == null ? null : p.getFileName().toString();
            if (fn != null && !fn.isBlank()) {
                Path alt = baseDir.resolve(fn).toAbsolutePath().normalize();
                if (Files.exists(alt)) {
                    return Files.newInputStream(alt);
                }
            }
        }

        // 기존 동작 유지: 현재 작업 디렉터리(user.dir) 기준 탐색
        if (Files.exists(p)) {
            return Files.newInputStream(p);
        }

        InputStream is = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(loc);

        if (is == null) {
            throw new IllegalStateException(
                    "Column mapping XLSX not found: " + loc + " (classpath or filesystem)"
            );
        }
        return is;
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
            var cs = ColumnMappingXlsxLoader.class.getProtectionDomain().getCodeSource();
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

    private String get(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return "";
        cell.setCellType(CellType.STRING);
        return cell.getStringCellValue().trim();
    }
}