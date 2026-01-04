package domain.text;

/**
 * SqlTextProvider의 결과(텍스트 + fallback 여부/출처).
 */
public final class SqlTextResolution {

    private final String sqlText;
    /**
     * 실제 SQL을 가져온 출처 (CSV/XML).
     */
    private final String source;
    private final boolean fallbackUsed;
    private final String fallbackSource;
    /**
     * sqlText가 비어있을 때(=skip) 원인 코드.
     */
    private final String emptyReason;

    private SqlTextResolution(String sqlText, String source, boolean fallbackUsed, String fallbackSource,
                              String emptyReason) {
        this.sqlText = sqlText;
        this.source = safe(source);
        this.fallbackUsed = fallbackUsed;
        this.fallbackSource = safeNull(fallbackSource);
        this.emptyReason = safeNull(emptyReason);
    }

    public static SqlTextResolution ofCsv(String sqlText) {
        return new SqlTextResolution(sqlText, "CSV", false, null, null);
    }

    /**
     * XML(MyBatis mapper)에서 찾은 SQL (fallback 아님).
     */
    public static SqlTextResolution ofXml(String sqlText) {
        return new SqlTextResolution(sqlText, "XML", false, null, null);
    }

    /**
     * fallback으로 SQL을 찾은 경우.
     *
     * @param source         CSV/XML
     * @param fallbackSource mapperFile 등
     */
    public static SqlTextResolution ofFallback(String sqlText, String source, String fallbackSource) {
        return new SqlTextResolution(sqlText, source, true, fallbackSource, null);
    }

    /**
     * 기존 시그니처 호환용 (source 미지정).
     */
    public static SqlTextResolution ofFallback(String sqlText, String fallbackSource) {
        return new SqlTextResolution(sqlText, "", true, fallbackSource, null);
    }

    public static SqlTextResolution empty(String source, String emptyReason) {
        return new SqlTextResolution(null, source, false, null, emptyReason);
    }

    /**
     * 기존 시그니처 호환용 (원인 미지정).
     */
    public static SqlTextResolution empty() {
        return new SqlTextResolution(null, "", false, null, null);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public String getSqlText() {
        return sqlText;
    }

    /**
     * CSV 또는 XML. (빈 문자열일 수 있음)
     */
    public String getSource() {
        return source;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    /**
     * mapperFile 등 fallback 출처(없으면 null)
     */
    public String getFallbackSource() {
        return fallbackSource;
    }

    /**
     * SQL이 비어 skip 될 때 원인 코드 (없으면 null/empty).
     */
    public String getEmptyReason() {
        return emptyReason;
    }
}
