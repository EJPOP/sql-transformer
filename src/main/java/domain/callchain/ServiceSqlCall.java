package domain.callchain;
public class ServiceSqlCall {

    private final String serviceClass;
    private final String serviceMethod;
    private final String mapperFile;
    private final String mapperNamespace;
    private final String sqlId;
    // optional (when CSV has these columns)
    private final String tag;
    private final String sqlText;

    public ServiceSqlCall(
            String serviceClass,
            String serviceMethod,
            String mapperFile,
            String mapperNamespace,
            String sqlId
    ) {
        this.serviceClass = serviceClass;
        this.serviceMethod = serviceMethod;
        this.mapperFile = mapperFile;
        this.mapperNamespace = mapperNamespace;
        this.sqlId = sqlId;
        this.tag = null;
        this.sqlText = null;
    }

    public ServiceSqlCall(
            String serviceClass,
            String serviceMethod,
            String mapperFile,
            String mapperNamespace,
            String tag,
            String sqlId,
            String sqlText
    ) {
        this.serviceClass = serviceClass;
        this.serviceMethod = serviceMethod;
        this.mapperFile = mapperFile;
        this.mapperNamespace = mapperNamespace;
        this.tag = tag;
        this.sqlId = sqlId;
        this.sqlText = sqlText;
    }

    public String getServiceClass() {
        return serviceClass;
    }

    public String getServiceMethod() {
        return serviceMethod;
    }

    public String getMapperFile() {
        return mapperFile;
    }

    public String getMapperNamespace() {
        return mapperNamespace;
    }

    public String getSqlId() {
        return sqlId;
    }

    public String getTag() {
        return tag;
    }

    public String getSqlText() {
        return sqlText;
    }

    @Override
    public String toString() {
        return "ServiceSqlCall{" +
                "serviceClass='" + serviceClass + '\'' +
                ", serviceMethod='" + serviceMethod + '\'' +
                ", mapperFile='" + mapperFile + '\'' +
                ", mapperNamespace='" + mapperNamespace + '\'' +
                ", tag='" + tag + '\'' +
                ", sqlId='" + sqlId + '\'' +
                ", sqlText='" + (sqlText == null ? null : (sqlText.length() > 60 ? sqlText.substring(0, 60) + "..." : sqlText)) + '\'' +
                '}';
    }
}
