package mybatis;

import domain.sql.SqlStatement;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MapperXmlLoader {

    public Map<String, SqlStatement> loadFromFile(Path xmlPath) {

        try (InputStream is = Files.newInputStream(xmlPath)) {

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

            // 🔒 외부 DTD / 엔티티 완전 차단 (망분리 대응 필수)
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            factory.setValidating(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader(""))
            );

            Document doc = builder.parse(is);

            Element root = doc.getDocumentElement();

            // ✅ mapper (MyBatis) 또는 sqlMap (iBATIS)
            String rootTag = root.getTagName();
            if (!"mapper".equals(rootTag) && !"sqlMap".equals(rootTag)) {
                return Map.of(); // 관심 없는 XML
            }

            String namespace = root.getAttribute("namespace");
            if (namespace == null || namespace.isBlank()) {
                return Map.of();
            }

            Map<String, SqlStatement> sqlMap = new HashMap<>();

            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {

                Node node = children.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;

                Element el = (Element) node;
                String tag = el.getTagName();

                // ✅ 공통 SQL 태그
                if (!isSqlTag(tag)) continue;

                String id = el.getAttribute("id");
                if (id == null || id.isBlank()) continue;

                String sqlText = extractSqlText(el);

                SqlStatement stmt = new SqlStatement(
                        xmlPath.toString(),
                        namespace,
                        id,
                        tag,
                        normalizeSql(sqlText)
                );

                sqlMap.put(id, stmt);
            }

            return sqlMap;

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse: " + xmlPath, e);
        }
    }

    private boolean isSqlTag(String tag) {
        return "select".equals(tag)
                || "insert".equals(tag)
                || "update".equals(tag)
                || "delete".equals(tag);
    }

    /**
     * ✅ MyBatis 동적 SQL을 위해 하위 노드까지 재귀적으로 TEXT/CDATA를 수집
     */
    private String extractSqlText(Element el) {
        StringBuilder sb = new StringBuilder();
        appendTextRec(el, sb);
        return sb.toString();
    }

    private void appendTextRec(Node node, StringBuilder sb) {
        if (node == null) return;

        short type = node.getNodeType();
        if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
            sb.append(node.getTextContent());
            return;
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendTextRec(children.item(i), sb);
        }
    }

    private String normalizeSql(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("\\s+", " ")
                .trim();
    }
}