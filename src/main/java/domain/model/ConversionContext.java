package domain.model;

/**
 * Per-SQL conversion context used for warning attribution.
 *
 * <p>Keep this immutable and very small (KISS). This allows warnings to include
 * a stable key: serviceClass + namespace + sqlId.</p>
 */
public final class ConversionContext {

    private final String serviceClass;
    private final String namespace;
    private final String sqlId;

    public ConversionContext(String serviceClass, String namespace, String sqlId) {
        this.serviceClass = safe(serviceClass);
        this.namespace = safe(namespace);
        this.sqlId = safe(sqlId);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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
}
