package infra.text;

import domain.sql.SqlStatement;
import domain.text.SqlTextProvider;
import domain.text.SqlTextResolution;
import mybatis.SqlStatementRegistry;

/**
 * CSV 우선 + Registry fallback 구현체.
 * - 문자열 리터럴 훼손 금지 원칙을 지키기 위해, 여기서는 "선택"만 하고 변환/파싱은 하지 않는다.
 */
public final class CsvFirstSqlTextProvider implements SqlTextProvider {

    private final SqlStatementRegistry registry;

    public CsvFirstSqlTextProvider(SqlStatementRegistry registry) {
        this.registry = registry;
    }

    private static NsId normalize(String namespace, String sqlId) {
        String ns = safe(namespace).trim();
        String id = safe(sqlId).trim();
        if (ns.isEmpty() || id.isEmpty()) return null;

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

        int dot = id.lastIndexOf('.');
        int colon = id.lastIndexOf(':');
        int cut = Math.max(dot, colon);
        if (cut > 0 && cut < id.length() - 1) {
            String left = id.substring(0, cut)
                    .trim();
            String right = id.substring(cut + 1)
                    .trim();
            if (!right.isEmpty() && (left.endsWith(ns) || ns.endsWith(left))) {
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
        if (!isBlank(csvSqlText)) {
            return SqlTextResolution.ofCsv(csvSqlText);
        }
        if (registry == null) {
            return SqlTextResolution.empty("XML", "REGISTRY_DISABLED");
        }

        try {
            SqlStatement stmt = lookup(namespace, sqlId);
            if (stmt == null) return SqlTextResolution.empty("XML", "XML_STATEMENT_NOT_FOUND");

            String txt = stmt.getSqlText();
            if (isBlank(txt)) return SqlTextResolution.empty("XML", "XML_SQL_TEXT_EMPTY");

            // mapperFile은 String/Path 어느 타입이든 toString()으로 안전하게 처리
            Object mf = null;
            try {
                mf = stmt.getMapperFile();
            } catch (Exception ignored) {
            }
            String src = (mf == null) ? null : String.valueOf(mf);

            return SqlTextResolution.ofFallback(txt, "XML", src);
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

        // 2) id가 "namespace.id" 또는 "namespace:id" 형태로 들어오는 케이스 방어
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