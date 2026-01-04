package domain.model;

/**
 * A single conversion outcome row for reporting.
 *
 * <p>Kept as a simple value object (no behavior). It is intentionally
 * not tied to any external library so it can be reused by CLI/API layers.</p>
 */
public final class AliasSqlResult {

    /**
     * e.g. SUCCESS / SKIP
     */
    private final String status;
    private final String serviceClass;
    private final String namespace;
    private final String sqlId;

    /**
     * optional reason message for SKIP
     */
    private final String message;

    /**
     * SQL 원문을 가져온 출처 (CSV/XML)
     */
    private final String sqlTextFrom;

    /**
     * fallback 사용 여부 (csv-first/xml-first에서 2순위를 사용했는지)
     */
    private final boolean fallbackUsed;

    /**
     * mapperFile 등 fallback 출처
     */
    private final String fallbackSource;

    /**
     * 옵션성 상세 정보 (skip 원인 상세, 예외 클래스 등)
     * - 대량 처리 시 필드가 비어있을 수 있다.
     */
    private final String detail;

    /**
     * 기존 호환 생성자 (status + key + message)
     */
    public AliasSqlResult(String status, String serviceClass, String namespace, String sqlId, String message) {
        this(status, serviceClass, namespace, sqlId, message, "", false, null, null);
    }

    public AliasSqlResult(
            String status,
            String serviceClass,
            String namespace,
            String sqlId,
            String message,
            String sqlTextFrom,
            boolean fallbackUsed,
            String fallbackSource,
            String detail
    ) {
        this.status = nullToEmpty(status);
        this.serviceClass = nullToEmpty(serviceClass);
        this.namespace = nullToEmpty(namespace);
        this.sqlId = nullToEmpty(sqlId);
        this.message = nullToEmpty(message);
        this.sqlTextFrom = nullToEmpty(sqlTextFrom);
        this.fallbackUsed = fallbackUsed;
        this.fallbackSource = nullToNullIfBlank(fallbackSource);
        this.detail = nullToNullIfBlank(detail);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String nullToNullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public String getStatus() {
        return status;
    }

    public String getServiceClass() {
        return serviceClass;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getSqlId() {
        return sqlId;
    }

    public String getMessage() {
        return message;
    }

    public String getSqlTextFrom() {
        return sqlTextFrom;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public String getFallbackSource() {
        return fallbackSource;
    }

    public String getDetail() {
        return detail;
    }
}
