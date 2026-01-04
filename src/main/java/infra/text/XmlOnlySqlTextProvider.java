package infra.text;

import domain.sql.SqlStatement;
import domain.text.SqlTextProvider;
import domain.text.SqlTextResolution;
import mybatis.SqlStatementRegistry;

/**
 * XML(MyBatis/iBATIS mapper)만 사용하여 SQL 원문을 찾는다.
 * - service_sql_xref.csv의 sql_text는 무시한다.
 */
public final class XmlOnlySqlTextProvider implements SqlTextProvider {

    private final SqlStatementRegistry registry;

    public XmlOnlySqlTextProvider(SqlStatementRegistry registry) {
        this.registry = registry;
    }

    private static NsId normalize(String namespace, String sqlId) {
        String ns = safe(namespace).trim();
        String id = safe(sqlId).trim();
        if (ns.isEmpty() || id.isEmpty()) return null;

        // id가 이미 "namespace.id" 형태로 들어온 경우
        String dotPrefix = ns + ".";
        String colonPrefix = ns + ":";
        if (id.startsWith(dotPrefix)) {
            return new NsId(ns, id.substring(dotPrefix.length())
                    .trim());
        }
        if (id.startsWith(colonPrefix)) {
            return new NsId(ns, id.substring(colonPrefix.length())
                    .trim());
        }

        // id 자체가 fully-qualified 형태이면 분해
        int dot = id.lastIndexOf('.');
        int colon = id.lastIndexOf(':');
        int cut = Math.max(dot, colon);
        if (cut > 0 && cut < id.length() - 1) {
            String left = id.substring(0, cut)
                    .trim();
            String right = id.substring(cut + 1)
                    .trim();
            if (!right.isEmpty() && (left.endsWith(ns) || ns.endsWith(left))) {
                // namespace는 left가 더 정확할 가능성이 높음
                String outNs = left.endsWith(ns) ? left : ns;
                return new NsId(outNs, right);
            }
        }

        return null;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim()
                .isEmpty();
    }

    @Override
    public SqlTextResolution resolve(String namespace, String sqlId, String csvSqlText) {
        if (registry == null) return SqlTextResolution.empty("XML", "REGISTRY_DISABLED");

        try {
            SqlStatement stmt = lookup(namespace, sqlId);
            if (stmt == null) return SqlTextResolution.empty("XML", "XML_STATEMENT_NOT_FOUND");

            String txt = stmt.getSqlText();
            if (isBlank(txt)) return SqlTextResolution.empty("XML", "XML_SQL_TEXT_EMPTY");

            return SqlTextResolution.ofXml(txt);
        } catch (Exception ignored) {
            return SqlTextResolution.empty("XML", "XML_LOOKUP_ERROR");
        }
    }

    private SqlStatement lookup(String namespace, String sqlId) {
        if (registry == null) return null;

        String ns = safe(namespace);
        String id = safe(sqlId);

        // 1) as-is
        SqlStatement stmt = registry.get(ns, id);
        if (stmt != null) return stmt;

        // 2) id가 "namespace.id"로 들어오는 케이스 방어
        NsId normalized = normalize(ns, id);
        if (normalized != null) {
            stmt = registry.get(normalized.namespace, normalized.sqlId);
        }
        return stmt;
    }

    private static final class NsId {
        final String namespace;
        final String sqlId;

        NsId(String namespace, String sqlId) {
            this.namespace = namespace;
            this.sqlId = sqlId;
        }
    }
}
