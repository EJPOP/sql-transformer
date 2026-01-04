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

    private static String escapeAttr(String v) {
        if (v == null) return "";
        // SQL 출력 문자열이므로 최소한의 안전치만 적용
        return v.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

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
     * ✅ MyBatis 동적 SQL 보존
     *
     * <p>기존 구현은 TEXT/CDATA만 수집해서 {@code <if>...</if>} 같은 동적 태그가 모두 사라지고,
     * 내부 텍스트만 남아 "조건부 컬럼"이 "항상 포함"으로 변질되는 문제가 있었다.</p>
     *
     * <p>따라서 statement 하위 노드를 순회하면서 TEXT/CDATA는 그대로 붙이고,
     * ELEMENT 노드는 태그(+속성)를 포함하여 직렬화한다.</p>
     */
    private String extractSqlText(Element stmtEl) {
        StringBuilder sb = new StringBuilder();

        // ✅ statement(el) 자체(<insert>...</insert>)는 포함하지 않고, 내부 노드만 SQL 텍스트로 취급
        NodeList children = stmtEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            appendNodePreservingXml(children.item(i), sb);
        }
        return sb.toString();
    }

    private void appendNodePreservingXml(Node node, StringBuilder sb) {
        if (node == null) return;

        short type = node.getNodeType();
        if (type == Node.TEXT_NODE) {
            sb.append(node.getNodeValue());
            return;
        }
        if (type == Node.CDATA_SECTION_NODE) {
            // ✅ CDATA 래퍼까지 보존 ("<" 등이 들어간 SQL도 안전)
            sb.append("<![CDATA[")
                    .append(node.getNodeValue())
                    .append("]]>");
            return;
        }

        if (type == Node.ELEMENT_NODE) {
            Element el = (Element) node;
            String name = el.getTagName();

            // start tag
            sb.append('<')
                    .append(name);
            var attrs = el.getAttributes();
            if (attrs != null) {
                for (int i = 0; i < attrs.getLength(); i++) {
                    Node a = attrs.item(i);
                    if (a == null) continue;
                    String an = a.getNodeName();
                    String av = a.getNodeValue();
                    sb.append(' ')
                            .append(an)
                            .append("=\"")
                            .append(escapeAttr(av))
                            .append("\"");
                }
            }

            // self-closing when no children
            NodeList kids = el.getChildNodes();
            if (kids == null || kids.getLength() == 0) {
                sb.append("/>");
                return;
            }

            sb.append('>');
            for (int i = 0; i < kids.getLength(); i++) {
                appendNodePreservingXml(kids.item(i), sb);
            }
            sb.append("</")
                    .append(name)
                    .append('>');
        }

        // ✅ COMMENT_NODE / PROCESSING_INSTRUCTION 등은 SQL에 직접 의미가 없는 경우가 많아 무시
    }

    private String normalizeSql(String sql) {
        if (sql == null) return "";

        // ✅ 동적 태그를 포함한 경우 공백을 과도하게 압축하면 태그/콤마 경계가 무너질 수 있다.
        // - line ending만 통일하고 outer trim만 적용
        String s = sql.replace("\r\n", "\n")
                .replace("\r", "\n");
        return s.trim();
    }
}