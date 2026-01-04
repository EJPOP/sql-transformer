package domain.output;

import java.util.Locale;

/**
 * File naming policy for generated SQL.
 * <p>
 * (NEW Requirement)
 * <sqlId>.sql
 * e.g. AEPAFU001ChangeStaApvRqsIdByApvRqsId.sql
 * <p>
 * NOTE:
 * - namespace는 폴더로 분리되므로 파일명에서 제거함
 * - namespace 폴더명은 구분자(/, \, _, 공백 등) 제거 후 붙여쓰기:
 * aep/afu/001  -> aepafu001
 * aep\afu\001  -> aepafu001
 * aep_afu_001  -> aepafu001
 */
public final class SqlFileNamePolicy {

    private SqlFileNamePolicy() {
    }

    /**
     * Build output filename: <sqlId>.sql
     */
    public static String build(String namespace, String sqlId) {
        String id = safePart(sqlId, "unknownId");
        id = limit(id, 180);
        return id + ".sql";
    }

    /**
     * Mapper/namespace folder name normalization
     * - FQCN이면 simpleName 사용
     * - 폴더명은 "알파벳/숫자만" 남기고 전부 제거하여 붙여쓰기
     */
    public static String extractMapperSimpleName(String namespace) {
        String ns = namespace == null ? "" : namespace.trim();
        if (ns.isEmpty()) return "UnknownMapper";

        // FQCN -> simple name
        int p = ns.lastIndexOf('.');
        String simple = (p >= 0) ? ns.substring(p + 1) : ns;

        // ✅ 핵심: /, \, _, 공백, 하이픈 등 "알파벳/숫자 이외"는 제거 → 붙여쓰기
        simple = simple.replaceAll("[^a-zA-Z0-9]", "");

        if (simple.isEmpty()) simple = "UnknownMapper";

        // 파일/폴더 안전 문자로 정규화 (현재는 이미 alnum이라 변화 거의 없음)
        simple = safePart(simple, "UnknownMapper");
        return limit(simple, 80);
    }

    private static String safePart(String raw, String fallback) {
        String s = (raw == null) ? "" : raw.trim();
        if (s.isEmpty()) s = fallback;
        s = s.replace('\n', '_')
                .replace('\r', '_');

        // Keep only filename-safe characters.
        s = s.replaceAll("[^a-zA-Z0-9._-]", "_");

        // avoid hidden/odd files on Windows
        if (s.startsWith(".")) s = "_" + s.substring(1);

        // windows reserved names protection (optional)
        String u = s.toUpperCase(Locale.ROOT);
        if (u.equals("CON") || u.equals("PRN") || u.equals("AUX") || u.equals("NUL")
                || u.matches("COM[1-9]") || u.matches("LPT[1-9]")) {
            s = "_" + s;
        }
        return s;
    }

    private static String limit(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
