package domain.model;

/**
 * Row model for TOBE DML parameters (reporting/analysis purpose).
 */
public final class TobeDmlParamRow {
    public final String serviceClass;
    public final String namespace;
    public final String sqlId;
    public final int seq;
    public final String dmlType;
    public final String paramName;
    public final String paramLowerCamel;

    public TobeDmlParamRow(
            String serviceClass,
            String namespace,
            String sqlId,
            int seq,
            String dmlType,
            String paramName,
            String paramLowerCamel
    ) {
        this.serviceClass = nullToEmpty(serviceClass);
        this.namespace = nullToEmpty(namespace);
        this.sqlId = nullToEmpty(sqlId);
        this.seq = seq;
        this.dmlType = nullToEmpty(dmlType);
        this.paramName = nullToEmpty(paramName);
        this.paramLowerCamel = nullToEmpty(paramLowerCamel);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
