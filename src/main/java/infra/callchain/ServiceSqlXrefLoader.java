package infra.callchain;

import org.apache.commons.csv.CSVFormat;

import org.apache.commons.csv.CSVParser;

import org.apache.commons.csv.CSVRecord;

import java.io.BufferedInputStream;

import java.io.IOException;

import java.io.InputStream;

import java.io.InputStreamReader;

import java.lang.reflect.Constructor;

import java.lang.reflect.RecordComponent;

import java.nio.charset.StandardCharsets;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.*;

import domain.callchain.ServiceSqlCall;

/**
 * service_sql_xref.csv 로더
 *
 * ✅ 지원 포맷
 * 1) (기존) 정규 포맷 헤더:
 *    service, service_method, file, namespace, id, tag, sql_text ...
 *
 * 2) (BEST/엑셀) 한글/혼합 헤더 + 빈 헤더 다수:
 *    ?Controller, RequestMapping, ... , Sevice메소드, ... , Sql-mapperID, query, ...
 *    + 뒤에 빈 컬럼들이 콤마로 계속 이어지는 형태도 허용
 *
 * ✅ 핵심: commons-csv의 withFirstRecordAsHeader()를 쓰지 않고,
 *        첫 row를 직접 헤더로 읽어 빈 헤더를 COL_n 으로 보정하여 파싱한다.
 */
public class ServiceSqlXrefLoader {

    public List<ServiceSqlCall> load(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("csv location is blank");
        }

        try (InputStream is = openStream(location);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT
                     .builder()
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) return Collections.emptyList();

            // ---------------------------
            // 1) header row (raw)
            // ---------------------------
            CSVRecord headerRec = it.next();
            List<String> headers = new ArrayList<>(headerRec.size());
            for (int i = 0; i < headerRec.size(); i++) {
                String h = safe(headerRec.get(i));
                h = stripBom(h).trim();
                if (h.isBlank()) h = "COL_" + (i + 1);
                headers.add(h);
            }

            // normalized header -> index (first wins)
            Map<String, Integer> headerIndex = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String key = norm(headers.get(i));
                headerIndex.putIfAbsent(key, i);
            }

            // detect best-like format quickly
            boolean bestLike = hasAnyHeader(headerIndex,
                    "sqlmapperid", "sql-mapperid", "sqlmapper", "sqlmapper_id", "sqlmapperid",
                    "sevice메소드", "service메소드", "query");

            // ---------------------------
            // 2) records
            // ---------------------------
            List<ServiceSqlCall> out = new ArrayList<>(1024);

            while (it.hasNext()) {
                CSVRecord r = it.next();

                // ---- sql text (optional)
                String sqlText = firstNonBlank(r, headerIndex, headers,
                        "sql_text", "sqltext", "query", "sql");

                // ---- tag (optional)
                String tag = firstNonBlank(r, headerIndex, headers,
                        "tag", "태그");

                // ---- mapper file (optional)
                String mapperFile = firstNonBlank(r, headerIndex, headers,
                        "file", "mapperfile", "mapper_file", "xml", "mapperxml");

                // ---- service class/method
                String serviceClass = firstNonBlank(r, headerIndex, headers,
                        "service", "serviceclass", "서비스", "서비스클래스");
                String serviceMethod = firstNonBlank(r, headerIndex, headers,
                        "service_method", "servicemethod", "method", "servicemethodname");

                // BEST에서 흔한 컬럼: "Sevice메소드" (오타 포함)
                String serviceFull = firstNonBlank(r, headerIndex, headers,
                        "sevice메소드", "service메소드", "servicemethodfull", "service_method_full");

                ServiceParts sp = parseServiceParts(serviceClass, serviceMethod, serviceFull);
                serviceClass = sp.serviceClass;
                serviceMethod = sp.serviceMethod;

                if (serviceClass.isBlank()) serviceClass = "UNKNOWN_SERVICE";
                if (serviceMethod.isBlank()) serviceMethod = "UNKNOWN_METHOD";

                // ---- namespace / sqlId
                String namespace = firstNonBlank(r, headerIndex, headers,
                        "namespace", "mappernamespace", "sqlnamespace", "ns");
                String sqlId = firstNonBlank(r, headerIndex, headers,
                        "id", "sqlid");

                // BEST에서 흔한 컬럼: "Sql-mapperID"
                String mapperId = firstNonBlank(r, headerIndex, headers,
                        "sql-mapperid", "sqlmapperid", "sqlmapper_id", "mapperid", "sqlmapper", "sql-mapper-id",
                        "sqlmapperID", "sql-mapperID"); // 대소문자/하이픈 변형 대비

                if ((namespace.isBlank() || sqlId.isBlank()) && !mapperId.isBlank()) {
                    NsId ni = splitNamespaceAndId(mapperId);
                    if (namespace.isBlank()) namespace = ni.namespace;
                    if (sqlId.isBlank()) sqlId = ni.sqlId;
                }

                // 최소 요건: namespace + id가 있어야 의미 있음
                if (namespace.isBlank() || sqlId.isBlank()) {
                    // BEST CSV 중 빈 row/헤더 꼬리 row 방어
                    continue;
                }

                ServiceSqlCall call = newCall(serviceClass, serviceMethod, mapperFile, namespace, tag, sqlId, sqlText);
                out.add(call);
            }

            if (bestLike) {
                System.out.println("[XREF] detected BEST-like csv. loaded=" + out.size());
            } else {
                System.out.println("[XREF] loaded=" + out.size());
            }

            return out;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load service_sql_xref.csv", e);
        }
    }

    // ------------------------------------------------------------
    // Instantiate ServiceSqlCall safely (record/constructor variations)
    // ------------------------------------------------------------
    private static ServiceSqlCall newCall(String serviceClass,
                                          String serviceMethod,
                                          String mapperFile,
                                          String namespace,
                                          String tag,
                                          String sqlId,
                                          String sqlText) {

        // 1) record면: record component 이름 기준으로 매핑 (가장 안전)
        try {
            if (ServiceSqlCall.class.isRecord()) {
                RecordComponent[] comps = ServiceSqlCall.class.getRecordComponents();
                Object[] args = new Object[comps.length];

                for (int i = 0; i < comps.length; i++) {
                    String n = comps[i].getName().toLowerCase(Locale.ROOT);

                    if (n.contains("service") && n.contains("class")) args[i] = serviceClass;
                    else if (n.contains("service") && n.contains("method")) args[i] = serviceMethod;
                    else if (n.contains("mapper") && n.contains("file")) args[i] = mapperFile;
                    else if ((n.contains("mapper") && n.contains("namespace")) || n.equals("namespace")) args[i] = namespace;
                    else if (n.equals("tag")) args[i] = tag;
                    else if (n.equals("id") || n.equals("sqlid") || n.contains("sql") && n.contains("id")) args[i] = sqlId;
                    else if (n.contains("sql") && n.contains("text")) args[i] = sqlText;
                    else args[i] = null;
                }

                // canonical ctor
                Class<?>[] types = Arrays.stream(comps).map(RecordComponent::getType).toArray(Class[]::new);
                Constructor<ServiceSqlCall> ctor = ServiceSqlCall.class.getDeclaredConstructor(types);
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        } catch (Exception ignored) {
        }

        // 2) 일반 class: 흔한 String ctor 시그니처를 순서대로 시도
        try {
            // (serviceClass, serviceMethod, mapperFile, namespace, tag, id, sqlText)
            Constructor<ServiceSqlCall> c7 = ServiceSqlCall.class.getConstructor(
                    String.class, String.class, String.class, String.class, String.class, String.class, String.class);
            return c7.newInstance(serviceClass, serviceMethod, mapperFile, namespace, tag, sqlId, sqlText);
        } catch (Exception ignored) {
        }

        try {
            // (serviceClass, serviceMethod, mapperFile, namespace, id)
            Constructor<ServiceSqlCall> c5 = ServiceSqlCall.class.getConstructor(
                    String.class, String.class, String.class, String.class, String.class);
            return c5.newInstance(serviceClass, serviceMethod, mapperFile, namespace, sqlId);
        } catch (Exception ignored) {
        }

        try {
            // (serviceClass, serviceMethod, namespace, id)
            Constructor<ServiceSqlCall> c4 = ServiceSqlCall.class.getConstructor(
                    String.class, String.class, String.class, String.class);
            return c4.newInstance(serviceClass, serviceMethod, namespace, sqlId);
        } catch (Exception ignored) {
        }

        throw new IllegalStateException(
                "Cannot instantiate ServiceSqlCall. Please check constructors/record components.");
    }

    // ------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------
    private static final class ServiceParts {
        final String serviceClass;
        final String serviceMethod;

        ServiceParts(String serviceClass, String serviceMethod) {
            this.serviceClass = serviceClass == null ? "" : serviceClass;
            this.serviceMethod = serviceMethod == null ? "" : serviceMethod;
        }
    }

    private static ServiceParts parseServiceParts(String serviceClass, String serviceMethod, String serviceFull) {
        String sc = safe(serviceClass).trim();
        String sm = safe(serviceMethod).trim();
        String sf = safe(serviceFull).trim();

        if (!sf.isBlank()) {
            // 예: com.xxx.AAABBBServiceImpl.selectList(...)  /  AAABBBServiceImpl.selectList
            sf = sf.replace("(", ".").replace(")", "");
            int sharp = sf.lastIndexOf('#');
            int dot = sf.lastIndexOf('.');
            int cut = Math.max(sharp, dot);
            if (cut > 0 && cut < sf.length() - 1) {
                String left = sf.substring(0, cut).trim();
                String right = sf.substring(cut + 1).trim();
                if (sc.isBlank()) sc = left;
                if (sm.isBlank()) sm = right;
            } else {
                if (sc.isBlank()) sc = sf;
            }
        }

        // 메소드명에 "xxx()" 같은 꼬리 제거
        if (sm.endsWith(")")) {
            int p = sm.indexOf('(');
            if (p > 0) sm = sm.substring(0, p);
        }

        return new ServiceParts(sc, sm);
    }

    private static final class NsId {
        final String namespace;
        final String sqlId;

        NsId(String namespace, String sqlId) {
            this.namespace = namespace == null ? "" : namespace;
            this.sqlId = sqlId == null ? "" : sqlId;
        }
    }

    private static NsId splitNamespaceAndId(String mapperId) {
        String s = safe(mapperId).trim();

        // 따옴표 제거
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }

        int dot = s.lastIndexOf('.');
        int colon = s.lastIndexOf(':');
        int cut = Math.max(dot, colon);

        if (cut > 0 && cut < s.length() - 1) {
            String ns = s.substring(0, cut).trim();
            String id = s.substring(cut + 1).trim();
            return new NsId(ns, id);
        }

        // 분리 실패: id만 있는 것으로 취급
        return new NsId("", s);
    }

    private static boolean hasAnyHeader(Map<String, Integer> headerIndex, String... keys) {
        for (String k : keys) {
            if (headerIndex.containsKey(norm(k))) return true;
        }
        return false;
    }

    private static String firstNonBlank(CSVRecord r,
                                        Map<String, Integer> headerIndex,
                                        List<String> headers,
                                        String... candidates) {
        Integer idx = findIndex(headerIndex, headers, candidates);
        if (idx == null) return "";
        if (idx < 0 || idx >= r.size()) return "";
        return safe(r.get(idx)).trim();
    }

    private static Integer findIndex(Map<String, Integer> headerIndex,
                                     List<String> headers,
                                     String... candidates) {
        if (candidates == null) return null;

        // 1) exact normalized match
        for (String c : candidates) {
            String k = norm(c);
            Integer i = headerIndex.get(k);
            if (i != null) return i;
        }

        // 2) contains match (BEST처럼 "Sql-mapperID" 등 변형 대비)
        for (String c : candidates) {
            String ck = norm(c);
            for (int i = 0; i < headers.size(); i++) {
                String hk = norm(headers.get(i));
                if (hk.contains(ck) || ck.contains(hk)) {
                    return i;
                }
            }
        }

        return null;
    }

    private static String norm(String s) {
        String t = stripBom(safe(s)).trim().toLowerCase(Locale.ROOT);
        // 문자/숫자만 남김(하이픈/공백/특수문자/물음표 제거)
        return t.replaceAll("[^\\p{L}\\p{Nd}]+", "");
    }

    private static String stripBom(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------
    // IO
    // ------------------------------------------------------------
    private InputStream openStream(String location) throws IOException {
        String s = location.trim();

        // classpath: 지원(옵션)
        if (s.startsWith("classpath:")) {
            String cp = s.substring("classpath:".length());
            InputStream is = ServiceSqlXrefLoader.class.getResourceAsStream(cp.startsWith("/") ? cp : ("/" + cp));
            if (is == null) throw new IOException("classpath resource not found: " + s);
            return new BufferedInputStream(is);
        }

        // file: URI 지원(옵션)
        if (s.startsWith("file:")) {
            try {
                Path p = Path.of(java.net.URI.create(s));
                return new BufferedInputStream(Files.newInputStream(p));
            } catch (Exception ignored) {
                // fallback below
            }
        }

        // 일반 경로
        Path p = Path.of(s);
        return new BufferedInputStream(Files.newInputStream(p));
    }
}