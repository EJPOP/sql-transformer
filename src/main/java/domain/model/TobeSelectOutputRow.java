package domain.model;
/** Row model for TOBE SELECT output columns (reporting/analysis purpose). */
public final class TobeSelectOutputRow {
    public final String serviceClass;
    public final String namespace;
    public final String sqlId;
    public final int seq;
    public final String outputName;
    public final String outputLowerCamel;
    public final String expression;
    public final String trailingComment;

    public TobeSelectOutputRow(
            String serviceClass,
            String namespace,
            String sqlId,
            int seq,
            String outputName,
            String outputLowerCamel,
            String expression,
            String trailingComment
    ) {
        this.serviceClass = nullToEmpty(serviceClass);
        this.namespace = nullToEmpty(namespace);
        this.sqlId = nullToEmpty(sqlId);
        this.seq = seq;
        this.outputName = nullToEmpty(outputName);
        this.outputLowerCamel = nullToEmpty(outputLowerCamel);
        this.expression = nullToEmpty(expression);
        this.trailingComment = nullToEmpty(trailingComment);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
