package domain.model;

/**
 * A single warning emitted during conversion.
 *
 * <p>Warnings are not fatal; they indicate a risk or missing information that
 * operators should review.</p>
 */
public final class ConversionWarning {

    private final WarningCode code;
    private final String serviceClass;
    private final String namespace;
    private final String sqlId;
    private final String message;
    private final String detail;

    public ConversionWarning(
            WarningCode code,
            String serviceClass,
            String namespace,
            String sqlId,
            String message,
            String detail
    ) {
        this.code = code == null ? WarningCode.TRANSFORM_ERROR : code;
        this.serviceClass = nullToEmpty(serviceClass);
        this.namespace = nullToEmpty(namespace);
        this.sqlId = nullToEmpty(sqlId);
        this.message = nullToEmpty(message);
        this.detail = nullToEmpty(detail);
    }

    public static ConversionWarning of(
            WarningCode code,
            String serviceClass,
            String namespace,
            String sqlId,
            String message
    ) {
        return new ConversionWarning(code, serviceClass, namespace, sqlId, message, "");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public WarningCode getCode() {
        return code;
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

    public String getDetail() {
        return detail;
    }
}
