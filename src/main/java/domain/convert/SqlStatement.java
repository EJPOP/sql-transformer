package domain.convert;
public class SqlStatement {

    private final String mapperFile;
    private final String namespace;
    private final String sqlId;
    private final String sqlType;   // select / insert / update / delete
    private final String sqlText;

    public SqlStatement(
            String mapperFile,
            String namespace,
            String sqlId,
            String sqlType,
            String sqlText
    ) {
        this.mapperFile = mapperFile;
        this.namespace = namespace;
        this.sqlId = sqlId;
        this.sqlType = sqlType;
        this.sqlText = sqlText;
    }

    public String getMapperFile() {
        return mapperFile;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getSqlId() {
        return sqlId;
    }

    public String getSqlType() {
        return sqlType;
    }

    public String getSqlText() {
        return sqlText;
    }
}
