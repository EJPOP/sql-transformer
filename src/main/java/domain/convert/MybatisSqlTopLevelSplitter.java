package domain.convert;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits SQL by comma or equals at top-level depth, ignoring strings/comments/params.
 */
final class MybatisSqlTopLevelSplitter {
    private MybatisSqlTopLevelSplitter() {
    }

    static List<String> splitTopLevelByComma(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        StringBuilder cur = new StringBuilder();
        MybatisSqlScan st = new MybatisSqlScan(s);
        int depth = 0;
        int xmlDepth = 0;

        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                cur.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                cur.append(st.readBlockComment());
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                cur.append(st.readSingleQuotedString());
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                cur.append(st.readDoubleQuotedString());
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                cur.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashParam()) {
                cur.append(st.readHashParam());
                continue;
            }
            if (st.peekIsCdata()) {
                cur.append(st.readCdata());
                continue;
            }
            if (st.peekIsXmlTag()) {
                String tag = st.readXmlTag();
                xmlDepth = updateXmlDepth(xmlDepth, tag);
                cur.append(tag);
                continue;
            }

            char ch = st.read();
            if (ch == '(') depth++;
            else if (ch == ')') depth = Math.max(0, depth - 1);

            if (ch == ',' && depth == 0 && xmlDepth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }

        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static int indexOfTopLevelEquals(String s) {
        MybatisSqlScan st = new MybatisSqlScan(s);
        int depth = 0;
        int idx = 0;

        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                st.readLineComment();
                idx = st.pos;
                continue;
            }
            if (st.peekIsBlockComment()) {
                st.readBlockComment();
                idx = st.pos;
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                st.readSingleQuotedString();
                idx = st.pos;
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                st.readDoubleQuotedString();
                idx = st.pos;
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                st.readMyBatisParam();
                idx = st.pos;
                continue;
            }
            if (st.peekIsHashParam()) {
                st.readHashParam();
                idx = st.pos;
                continue;
            }
            if (st.peekIsCdata()) {
                st.readCdata();
                idx = st.pos;
                continue;
            }
            if (st.peekIsXmlTag()) {
                st.readXmlTag();
                idx = st.pos;
                continue;
            }

            char ch = st.read();
            if (ch == '(') depth++;
            else if (ch == ')') depth = Math.max(0, depth - 1);
            else if (ch == '=' && depth == 0) return idx;

            idx = st.pos;
        }
        return -1;
    }

    /**
     * Track XML(tag) depth to avoid splitting inside MyBatis dynamic tags.
     * We treat non-self-closing open tags as +1 and corresponding close tags as -1.
     * Special tags like <![CDATA[ ... ]]> or processing instructions are ignored.
     */
    static int updateXmlDepth(int current, String tag) {
        if (tag == null) return current;
        String t = tag.trim();
        if (!t.startsWith("<")) return current;
        // ignore declarations / directives
        if (t.startsWith("<!") || t.startsWith("<?")) return current;

        boolean closing = t.startsWith("</");
        boolean selfClosing = isSelfClosingXmlTag(t);
        String name = extractXmlTagName(t);
        if (name.isEmpty()) return current;

        if (closing) {
            return Math.max(0, current - 1);
        }
        if (selfClosing) {
            return current;
        }
        return current + 1;
    }

    private static boolean isSelfClosingXmlTag(String t) {
        if (t == null) return false;

        // quick path
        String s = t.trim();
        if (s.endsWith("/>")) return true;

        // find last '>' and check last non-space char before it
        int gt = s.lastIndexOf('>');
        if (gt <= 0) return false;

        int i = gt - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i >= 0 && s.charAt(i) == '/';
    }

    static String extractXmlTagName(String tag) {
        String t = tag.trim();
        if (t.startsWith("</")) t = t.substring(2);
        else if (t.startsWith("<")) t = t.substring(1);
        t = t.trim();
        if (t.isEmpty()) return "";
        // up to whitespace or '>' or '/'
        int end = 0;
        while (end < t.length()) {
            char c = t.charAt(end);
            if (Character.isWhitespace(c) || c == '>' || c == '/') break;
            end++;
        }
        if (end <= 0) return "";
        String name = t.substring(0, end);
        // normalize
        return name.replaceAll("[^a-zA-Z0-9_:-]", "");
    }

}
