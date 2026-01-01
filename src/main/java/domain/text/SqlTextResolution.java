package domain.text;
/** SqlTextProvider의 결과(텍스트 + fallback 여부/출처). */
public final class SqlTextResolution {

    private final String sqlText;
    private final boolean fallbackUsed;
    private final String fallbackSource;

    private SqlTextResolution(String sqlText, boolean fallbackUsed, String fallbackSource) {
        this.sqlText = sqlText;
        this.fallbackUsed = fallbackUsed;
        this.fallbackSource = fallbackSource;
    }

    public static SqlTextResolution ofCsv(String sqlText) {
        return new SqlTextResolution(sqlText, false, null);
    }

    public static SqlTextResolution ofFallback(String sqlText, String fallbackSource) {
        return new SqlTextResolution(sqlText, true, fallbackSource);
    }

    public static SqlTextResolution empty() {
        return new SqlTextResolution(null, false, null);
    }

    public String getSqlText() {
        return sqlText;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    /** mapperFile 등 fallback 출처(없으면 null) */
    public String getFallbackSource() {
        return fallbackSource;
    }
}
