package domain.model;
/**
 * A single conversion outcome row for reporting.
 *
 * <p>Kept as a simple value object (no behavior). It is intentionally
 * not tied to any external library so it can be reused by CLI/API layers.</p>
 */
public final class AliasSqlResult {

    /** e.g. SUCCESS / SKIP */
    private final String status;
    private final String serviceClass;
    private final String namespace;
    private final String sqlId;
    /** optional reason message for SKIP */
    private final String message;

    public AliasSqlResult(String status, String serviceClass, String namespace, String sqlId, String message) {
        this.status = nullToEmpty(status);
        this.serviceClass = nullToEmpty(serviceClass);
        this.namespace = nullToEmpty(namespace);
        this.sqlId = nullToEmpty(sqlId);
        this.message = nullToEmpty(message);
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
