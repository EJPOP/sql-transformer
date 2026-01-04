package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingLookup;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static ch.qos.logback.core.joran.util.beans.BeanUtil.toLowerCamelCase;

/**
 * Renames MyBatis "#{...}" / "${...}" parameters to match TOBE column ids in lowerCamelCase.
 *
 * <p>Goals:
 * <ul>
 *   <li>Work on MyBatis XML text (tags + CDATA) safely: never corrupt XML tags.</li>
 *   <li>Work inside <![CDATA[ ... ]]> as SQL-only text.</li>
 *   <li>Infer param->column mapping from patterns like "COL = #{param}" (alias-qualified too).</li>
 *   <li>Fallback: if no mapping exists, rename based on the left column id (e.g. ADT_YR -> adtYr).</li>
 * </ul>
 */
public class MybatisBraceParamRenamer {

    private final ColumnMappingLookup registry;

    public MybatisBraceParamRenamer(ColumnMappingLookup registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public String rename(String text, Map<String, String> aliasTableMap) {
        if (text == null || text.isBlank()) return text;
        return rewrite(text, aliasTableMap, true);
    }


    /**
     * Rewrite OGNL identifiers inside selected MyBatis XML tag attributes (e.g., {@code <if test="...">}).
     *
     * <p>We do NOT attempt to parse full XML; we conservatively rewrite only attribute values for
     * known OGNL-bearing attributes (test/value/collection). This prevents breaking tag structure
     * while still keeping renamed params consistent with {@code #{...}} replacements.
     *
     * <p><b>Important:</b> we must NOT treat '=' inside attribute values as an attribute delimiter.
     * Earlier implementations that scanned raw characters were prone to corrupting
     * {@code test="a != null and b == ''"} into broken tags. This method only rewrites within
     * properly quoted attribute assignments.
     */
    private String rewriteXmlTagAttributes(String tag, Map<String, String> paramToTobeCol) {
        if (tag == null || tag.isBlank()) return tag;

        // Do not touch comments, processing instructions, doctype, CDATA declarations, etc.
        String t = tag.trim();
        if (t.startsWith("<!--") || t.startsWith("<?") || t.startsWith("<!")) {
            return tag;
        }

        // Rewrite only quoted values of OGNL attributes.
        // Keep original spacing around '=' and keep original quote style.
        final java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?is)\b(test|value|collection)\b(\s*=\s*)(\"([^\"]*)\"|'([^']*)')"
        );

        java.util.regex.Matcher m = p.matcher(tag);
        StringBuffer sb = new StringBuffer(tag.length() + 16);

        while (m.find()) {
            String attr = m.group(1);
            String eq = m.group(2);
            String quoted = m.group(3);

            String val = (m.group(4) != null) ? m.group(4) : m.group(5);
            char quote = quoted.charAt(0);

            String rewritten = rewriteOgnlExpression(val, paramToTobeCol);

            String replacement = attr + eq + quote + rewritten + quote;
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);

        return sb.toString();
    }

    private boolean isOgnlAttribute(String attrName) {
        if (attrName == null) return false;
        String n = attrName.trim().toLowerCase(Locale.ROOT);
        return n.equals("test") || n.equals("value") || n.equals("collection");
    }

    /**
     * Very small OGNL tokenizer: rewrites bare identifiers (and property leaf identifiers)
     * outside of quoted literals.
     */
    private String rewriteOgnlExpression(String expr, Map<String, String> paramToTobeCol) {
        if (expr == null || expr.isBlank()) return expr;

        StringBuilder out = new StringBuilder(expr.length() + 8);

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < expr.length(); ) {
            char c = expr.charAt(i);

            // toggle quote states (ignore escaped quotes)
            if (!inDouble && c == '\'' && (i == 0 || expr.charAt(i - 1) != '\\')) {
                inSingle = !inSingle;
                out.append(c);
                i++;
                continue;
            }
            if (!inSingle && c == '"' && (i == 0 || expr.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
                out.append(c);
                i++;
                continue;
            }

            if (inSingle || inDouble) {
                out.append(c);
                i++;
                continue;
            }

            if (isIdentifierStart(c)) {
                int j = i + 1;
                while (j < expr.length() && isIdentifierPart(expr.charAt(j))) j++;

                String ident = expr.substring(i, j);

                // avoid rewriting common OGNL keywords / literals
                if (isOgnlKeyword(ident)) {
                    out.append(ident);
                } else {
                    // avoid rewriting method names: foo(...) patterns
                    int k = j;
                    while (k < expr.length() && Character.isWhitespace(expr.charAt(k))) k++;
                    if (k < expr.length() && expr.charAt(k) == '(') {
                        out.append(ident);
                    } else {
                        out.append(rewriteBareIdentifier(ident, paramToTobeCol));
                    }
                }

                i = j;
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private boolean isOgnlKeyword(String ident) {
        String x = ident.toLowerCase(Locale.ROOT);
        return x.equals("and") || x.equals("or") || x.equals("not")
                || x.equals("null") || x.equals("true") || x.equals("false")
                || x.equals("instanceof") || x.equals("in");
    }

    /**
     * Rewrite a bare identifier using the same mapping logic as {@code #{...}} leaf rewrite.
     * If no mapping applies, returns the original identifier.
     */
    private String rewriteBareIdentifier(String ident, Map<String, String> paramToTobeCol) {
        if (ident == null || ident.isBlank()) return ident;

        // In OGNL we can see property paths; rewrite only the leaf token if we can.
        // Example: item.audYr -> item.adtYr
        int dot = ident.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < ident.length()) {
            String prefix = ident.substring(0, dot + 1);
            String leaf = ident.substring(dot + 1);
            String newLeaf = rewriteParamLeafByTobeColumn(leaf, paramToTobeCol);
            return prefix + newLeaf;
        }

        return rewriteParamLeafByTobeColumn(ident, paramToTobeCol);
    }

    private String rewriteParamLeafByTobeColumn(String leaf, Map<String, String> paramToTobeCol) {
        if (leaf == null || leaf.isBlank()) return leaf;

        String upper = leaf.trim().toUpperCase(Locale.ROOT);

        // direct learned mapping from SQL: leaf -> TOBE_COLUMN_ID
        String tobeCol = paramToTobeCol.get(upper);
        if (tobeCol != null && !tobeCol.isBlank()) {
            return toLowerCamelCase(tobeCol);
        }

        // If leaf itself looks like an ASIS column-id, try registry mapping first.
        // This is critical for OGNL like: <if test="STYPE != null"> ... </if>
        // where STYPE should become sType based on column mapping (not naive sTYPE).
        if (looksLikeColumnId(upper)) {
            ColumnMapping cm = registry.findByColumnOnly(upper);
            if (cm != null && !registry.isDeleted(cm) && cm.tobeColumnId != null && !cm.tobeColumnId.isBlank()) {
                return toLowerCamel(cm.tobeColumnId);
            }
            // try a reasonable snake conversion for mixed-case identifiers
            String snake = camelToUpperSnake(leaf);
            if (snake != null && !snake.isBlank()) {
                ColumnMapping cm2 = registry.findByColumnOnly(snake.toUpperCase(Locale.ROOT));
                if (cm2 != null && !registry.isDeleted(cm2) && cm2.tobeColumnId != null && !cm2.tobeColumnId.isBlank()) {
                    return toLowerCamel(cm2.tobeColumnId);
                }
            }
            // final fallback: camelize the column-id itself
            return toLowerCamel(upper);
        }

        return leaf;
    }


    // ==========================================================
    // Rewrite (safe, xml-aware)
    // ==========================================================

    private String rewrite(String text, Map<String, String> aliasTableMap, boolean xmlAware) {
        if (text == null || text.isBlank()) return text;

        // 1) infer (param -> tobeColumnId) from conditions/assignments
        Map<String, String> paramToTobeCol = inferParamToTobeColumn(text, aliasTableMap, xmlAware);

        // 2) rewrite tokens safely (no backtracking that could corrupt XML)
        SqlScan st = new SqlScan(text);
        StringBuilder out = new StringBuilder(text.length() + 64);
        RollingWindow w = new RollingWindow();

        while (st.hasNext()) {

            // XML tags are copied verbatim in xmlAware mode
            if (xmlAware && st.peekIsXmlTag()) {
                String tag = st.readXmlTag();
                out.append(rewriteXmlTagAttributes(tag, paramToTobeCol));
                w.reset();
                continue;
            }

            // CDATA is rewritten recursively as SQL-only, then wrapped back
            if (xmlAware && st.peekIsCdata()) {
                String raw = st.readCdata();
                String inner = CdataUtil.innerOf(raw);
                if (inner != null) {
                    String rewrittenInner = rewrite(inner, aliasTableMap, false);
                    out.append(CdataUtil.wrapWithSameCdata(raw, rewrittenInner));
                } else {
                    out.append(raw);
                }
                w.reset();
                continue;
            }

            // comments: append as-is. DO NOT reset window (comments may appear between column and operator).
            if (st.peekIsLineComment()) {
                out.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                out.append(st.readBlockComment());
                continue;
            }

            // strings: append as-is and reset window
            if (st.peekIsSingleQuotedString()) {
                out.append(st.readSingleQuotedString());
                w.reset();
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                out.append(st.readDoubleQuotedString());
                w.reset();
                continue;
            }

            // MyBatis param token
            if (st.peekIsMyBatisParam()) {
                String tok = st.readMyBatisParam();

                // On-the-fly inference (helps when the 1st-pass inference misses due to formatting)
                String leaf = extractParamLeafName(tok);
                if (leaf != null && !leaf.isBlank()) {
                    String key = leaf.toUpperCase(Locale.ROOT);
                    if (!paramToTobeCol.containsKey(key)) {
                        InferredColumnRef ref = w.tryInferLeftColumn();
                        if (ref != null) {
                            String tobe = resolveTobeColumnId(aliasTableMap, ref.alias, ref.column);
                            if (tobe == null || tobe.isBlank()) {
                                tobe = guessColumnIdFromLeft(ref.column);
                            }
                            if (tobe != null && !tobe.isBlank()) {
                                paramToTobeCol.putIfAbsent(key, tobe);
                            }
                        }
                    }
                }

                out.append(rewriteMyBatisParamToken(tok, paramToTobeCol));
                w.push(new Token(TokenType.PARAM, tok));
                continue;
            }

            // whitespace
            char c = st.peek();
            if (Character.isWhitespace(c)) {
                out.append(st.read());
                continue;
            }

            // words
            if (isWordStart(c)) {
                String word = st.readWord();
                out.append(word);
                w.push(new Token(TokenType.WORD, word));
                continue;
            }

            // symbols (operators / dot / parens / commas)
            if (isSymbolOfInterest(c)) {
                out.append(st.read());
                w.push(new Token(TokenType.SYM, String.valueOf(c)));
                continue;
            }

            // default: copy 1 char
            out.append(st.read());
        }

        return out.toString();
    }

    // ==========================================================
    // Inference
    // ==========================================================

    private Map<String, String> inferParamToTobeColumn(String text, Map<String, String> aliasTableMap, boolean xmlAware) {
        Map<String, String> map = new HashMap<>();
        if (text == null || text.isBlank()) return map;

        // For SQL-only regions, add regex inference to cover complex spacing/comment patterns.
        if (!xmlAware) {
            map.putAll(inferByRegex(text, aliasTableMap));
        }

        RollingWindow w = new RollingWindow();
        SqlScan st = new SqlScan(text);

        while (st.hasNext()) {
            // skip tags/cdata in xml-aware mode (handled recursively)
            if (xmlAware && st.peekIsCdata()) {
                String raw = st.readCdata();
                String inner = CdataUtil.innerOf(raw);
                if (inner != null && !inner.isBlank()) {
                    map.putAll(inferParamToTobeColumn(inner, aliasTableMap, false));
                }
                w.reset();
                continue;
            }
            if (xmlAware && st.peekIsXmlTag()) {
                st.readXmlTag();
                w.reset();
                continue;
            }

            if (st.peekIsLineComment()) { st.readLineComment(); continue; }
            if (st.peekIsBlockComment()) { st.readBlockComment(); continue; }

            if (st.peekIsSingleQuotedString()) { st.readSingleQuotedString(); w.reset(); continue; }
            if (st.peekIsDoubleQuotedString()) { st.readDoubleQuotedString(); w.reset(); continue; }

            if (st.peekIsMyBatisParam()) {
                String tok = st.readMyBatisParam();
                String leaf = extractParamLeafName(tok);
                if (leaf != null) {
                    InferredColumnRef ref = w.tryInferLeftColumn();
                    if (ref != null) {
                        String tobeCol = resolveTobeColumnId(aliasTableMap, ref.alias, ref.column);
                        if (tobeCol == null || tobeCol.isBlank()) tobeCol = guessColumnIdFromLeft(ref.column);
                        if (tobeCol != null && !tobeCol.isBlank()) {
                            map.putIfAbsent(leaf.toUpperCase(Locale.ROOT), tobeCol);
                        }
                    }
                }
                w.push(new Token(TokenType.PARAM, tok));
                continue;
            }

            char c = st.peek();
            if (Character.isWhitespace(c)) { st.read(); continue; }

            if (isWordStart(c)) {
                String word = st.readWord();
                w.push(new Token(TokenType.WORD, word));
                continue;
            }

            if (isSymbolOfInterest(c)) {
                w.push(new Token(TokenType.SYM, String.valueOf(c)));
                st.read();
                continue;
            }

            st.read();
        }

        // positional mapping for INSERT ... VALUES ...
        map.putAll(inferFromInsertValues(text, aliasTableMap));

        return map;
    }

    /**
     * Infer param->column mapping by regex, for SQL-only text.
     *
     * Handles patterns like:
     *  - COL = #{param}
    */
    private Map<String, String> inferByRegex(String sql, Map<String, String> aliasTableMap) {
        Map<String, String> map = new HashMap<>();
        if (sql == null || sql.isBlank()) return map;

        // (?is) = case-insensitive + dotall
        // alias optional, column required. Inline block comments allowed between tokens.
        // Allow optional function wrappers between operator and #{...}, e.g. "= SUBSTR( #{p}, ... )".
        final java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?is)(?:\\b([A-Z][A-Z0-9_]*)\\b\\s*\\.\\s*)?\\b([A-Z][A-Z0-9_]*)\\b" +
                        "(?:\\s*/\\*.*?\\*/\\s*)*" +
                        "(=|<>|!=|<=|>=|<|>|\\bLIKE\\b|\\bIN\\b)" +
                        "(?:\\s*/\\*.*?\\*/\\s*)*" +
                        "(?:\\b[A-Z][A-Z0-9_]*\\b\\s*\\(\\s*)*" +
                        "([#$])\\{\\s*([^}]+?)\\s*\\}"
        );

        java.util.regex.Matcher m = p.matcher(sql);
        while (m.find()) {
            String alias = m.group(1);
            String col = m.group(2);

            String inner = m.group(5); // inside braces
            String token = m.group(4) + "{" + inner + "}";

            String leaf = extractParamLeafName(token);
            if (leaf == null || leaf.isBlank()) continue;

            String tobeCol = resolveTobeColumnId(aliasTableMap, alias, col);
            if (tobeCol == null || tobeCol.isBlank()) tobeCol = guessColumnIdFromLeft(col);
            if (tobeCol == null || tobeCol.isBlank()) continue;

            map.putIfAbsent(leaf.toUpperCase(Locale.ROOT), tobeCol);
        }

        return map;
    }

    // ==========================================================
    // Token rewrite
    // ==========================================================

    private String rewriteMyBatisParamToken(String token, Map<String, String> paramToTobeCol) {
        if (token == null || token.length() < 4) return token;

        int braceOpen = token.indexOf('{');
        int braceClose = token.lastIndexOf('}');
        if (braceOpen < 0 || braceClose < 0 || braceClose <= braceOpen) return token;

        String inner = token.substring(braceOpen + 1, braceClose);
        String leadingWs = leadingWhitespace(inner);
        String trimmed = inner.trim();

        // name until ',' or whitespace
        int cut = indexOfParamNameEnd(trimmed);
        String namePart = (cut < 0) ? trimmed : trimmed.substring(0, cut);
        String rest = (cut < 0) ? "" : trimmed.substring(cut);

        // Keep any property path like "item.xxx"
        String pathPrefix = "";
        String leaf = namePart;
        int dot = namePart.lastIndexOf('.');
        if (dot >= 0) {
            pathPrefix = namePart.substring(0, dot + 1);
            leaf = namePart.substring(dot + 1);
        }

        String leafUpper = leaf.toUpperCase(Locale.ROOT);
        String tobeCol = (paramToTobeCol == null) ? null : paramToTobeCol.get(leafUpper);

        String newLeaf;
        if (tobeCol != null && !tobeCol.isBlank()) {
            newLeaf = toLowerCamel(tobeCol);
        } else {
            // Fallback-1: try registry lookup by column-only (handle audYr-like -> AUD_YR)
            String candidate = looksLikeColumnId(leaf) ? leafUpper : camelToUpperSnake(leaf);
            ColumnMapping cm = (candidate == null) ? null : registry.findByColumnOnly(candidate);
            if (cm != null && !registry.isDeleted(cm) && cm.tobeColumnId != null && !cm.tobeColumnId.isBlank()) {
                newLeaf = toLowerCamel(cm.tobeColumnId);
            } else {
                // Fallback-2: if it already looks like a column id, camelize it
                if (looksLikeColumnId(leafUpper)) newLeaf = toLowerCamel(leafUpper);
                else newLeaf = leaf;
            }
        }

        String newInner = leadingWs + pathPrefix + newLeaf + rest;
        return token.substring(0, braceOpen + 1) + newInner + "}";
    }

    // ==========================================================
    // Mapping resolution helpers
    // ==========================================================

    private String resolveTobeColumnId(Map<String, String> aliasTableMap, String alias, String col) {
        String colU = upper(col);
        if (colU.isBlank()) return null;

        // 1) qualified with alias -> table
        if (alias != null && aliasTableMap != null) {
            String table = aliasTableMap.get(upper(alias));
            if (table == null) table = aliasTableMap.get(alias);

            if (table != null) {
                ColumnMapping cm = registry.find(table, colU);
                if (cm != null && !registry.isDeleted(cm)) return cm.tobeColumnId;

                // cover case where "table" might already be TOBE id
                ColumnMapping cm2 = registry.findByTobeOnAsisTable(table, colU);
                if (cm2 != null && !registry.isDeleted(cm2)) return cm2.tobeColumnId;
            }
        }

        // 2) unqualified: try ASIS column-only
        ColumnMapping m1 = registry.findByColumnOnly(colU);
        if (m1 != null && !registry.isDeleted(m1)) return m1.tobeColumnId;

        return null;
    }

    private static String guessColumnIdFromLeft(String leftColumnToken) {
        if (leftColumnToken == null) return null;
        String colU = upper(leftColumnToken);
        if (colU.isBlank()) return null;

        if (looksLikeColumnId(colU)) return colU;

        String snake = camelToUpperSnake(leftColumnToken);
        if (snake != null && !snake.isBlank()) {
            String s = upper(snake);
            if (looksLikeColumnId(s)) return s;
        }
        return null;
    }

    // ==========================================================
    // INSERT ... VALUES positional inference
    // ==========================================================

    private Map<String, String> inferFromInsertValues(String sql, Map<String, String> aliasTableMap) {
        Map<String, String> map = new HashMap<>();
        if (sql == null || sql.isBlank()) return map;

        int from = 0;
        String upper = sql.toUpperCase(Locale.ROOT);

        while (true) {
            int idxInsert = upper.indexOf("INSERT", from);
            if (idxInsert < 0) break;

            int idxColsOpen = upper.indexOf("(", idxInsert);
            if (idxColsOpen < 0) { from = idxInsert + 6; continue; }

            int idxColsClose = findMatchingParen(sql, idxColsOpen);
            if (idxColsClose < 0) { from = idxColsOpen + 1; continue; }

            int idxValues = upper.indexOf("VALUES", idxColsClose);
            if (idxValues < 0) { from = idxColsClose + 1; continue; }

            int idxValsOpen = upper.indexOf("(", idxValues);
            if (idxValsOpen < 0) { from = idxValues + 6; continue; }

            int idxValsClose = findMatchingParen(sql, idxValsOpen);
            if (idxValsClose < 0) { from = idxValsOpen + 1; continue; }

            String colsRaw = sql.substring(idxColsOpen + 1, idxColsClose);
            String valsRaw = sql.substring(idxValsOpen + 1, idxValsClose);

            var cols = splitTopLevelComma(colsRaw);
            var vals = splitTopLevelComma(valsRaw);

            int n = Math.min(cols.size(), vals.size());
            for (int i = 0; i < n; i++) {
                String col = normalizeColumnToken(cols.get(i));
                if (col.isBlank()) continue;

                String tobeCol = resolveTobeColumnId(aliasTableMap, null, col);
                if (tobeCol == null || tobeCol.isBlank()) tobeCol = guessColumnIdFromLeft(col);
                if (tobeCol == null || tobeCol.isBlank()) continue;

                for (String tok : extractMyBatisTokens(vals.get(i))) {
                    String leaf = extractParamLeafName(tok);
                    if (leaf == null) continue;
                    map.putIfAbsent(leaf.toUpperCase(Locale.ROOT), tobeCol);
                }
            }

            from = idxValsClose + 1;
        }

        return map;
    }

    // ==========================================================
    // Utilities
    // ==========================================================

    private static String upper(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";
        return t.toUpperCase(Locale.ROOT);
    }

    private static boolean looksLikeColumnId(String s) {
        if (s == null || s.isBlank()) return false;
        if (s.indexOf('_') >= 0) return true;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isUpperCase(c) || Character.isDigit(c))) return false;
        }
        return true;
    }

    private static String camelToUpperSnake(String s) {
        if (s == null || s.isBlank()) return null;
        if (looksLikeColumnId(s.toUpperCase(Locale.ROOT))) return s.toUpperCase(Locale.ROOT);

        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) b.append('_');
            if (ch == '-') b.append('_');
            else b.append(Character.toUpperCase(ch));
        }
        return b.toString();
    }

    private static String toLowerCamel(String id) {
        if (id == null) return "";
        String s = id.trim();
        if (s.isEmpty()) return s;

        if (s.indexOf('_') < 0) {
            if (s.length() == 1) return s.toLowerCase(Locale.ROOT);
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }

        String[] parts = s.toLowerCase(Locale.ROOT).split("_+");
        StringBuilder b = new StringBuilder(s.length());
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() == 0) b.append(p);
            else {
                b.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) b.append(p.substring(1));
            }
        }
        return b.toString();
    }

    private static String extractParamLeafName(String token) {
        if (token == null || token.length() < 4) return null;
        int braceOpen = token.indexOf('{');
        int braceClose = token.lastIndexOf('}');
        if (braceOpen < 0 || braceClose < 0 || braceClose <= braceOpen) return null;

        String inner = token.substring(braceOpen + 1, braceClose).trim();
        int cut = indexOfParamNameEnd(inner);
        String name = (cut < 0) ? inner : inner.substring(0, cut);
        if (name.isBlank()) return null;

        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) name = name.substring(dot + 1);
        return name.trim();
    }

    private static int indexOfParamNameEnd(String s) {
        if (s == null) return -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == ',') return i;
        }
        return -1;
    }

    private static String leadingWhitespace(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || Character.isDigit(c);
    }

    private static boolean isSymbolOfInterest(char c) {
        return c == '.' || c == '=' || c == '>' || c == '<' || c == '!' || c == ',' || c == '(' || c == ')';
    }

    // ==========================================================
    // Rolling window inference
    // ==========================================================

    private enum TokenType { WORD, SYM, PARAM }
    private record Token(TokenType type, String text) {}

    private static final class RollingWindow {
        private final Token[] buf = new Token[8];
        private int pos = 0;

        void push(Token t) { buf[pos % buf.length] = t; pos++; }
        void reset() { pos = 0; for (int i = 0; i < buf.length; i++) buf[i] = null; }

        InferredColumnRef tryInferLeftColumn() {
            // Called right before a PARAM token is pushed.
            // Supported patterns:
            //   [ALIAS .] COL (=, >=, <=, <>, !=, >, <) #{param}
            //   [ALIAS .] COL LIKE #{param}
            //   [ALIAS .] COL IN #{param}
            //   [ALIAS .] COL IN ( #{param} ... )
            //   [ALIAS .] COL = FUNC( #{param} ... )   <-- function wrapper (SUBSTR/TO_CHAR/etc)

            Token t1 = at(-1);
            if (t1 == null) return null;

            // IN ( #{param}
            if (t1.type == TokenType.SYM && "(".equals(t1.text)) {
                Token tIn = at(-2);
                if (tIn != null && tIn.type == TokenType.WORD && "IN".equalsIgnoreCase(tIn.text)) {
                    return inferColumnAt(-3);
                }

                // Function wrapper: OP FUNC (
                Token tFunc = at(-2);
                Token tOp = at(-3);
                if (tFunc != null && tFunc.type == TokenType.WORD && tOp != null) {
                    if ((tOp.type == TokenType.SYM && isOperatorSym(tOp.text))
                            || (tOp.type == TokenType.WORD && isWordOperator(tOp.text))) {
                        return inferColumnAt(-4);
                    }
                }

                // generic scan backwards for an operator
                InferredColumnRef ref = scanBackwardForOperator();
                if (ref != null) return ref;
            }

            // LIKE/IN before param
            if (t1.type == TokenType.WORD) {
                String op = t1.text == null ? "" : t1.text.trim();
                if ("LIKE".equalsIgnoreCase(op) || "IN".equalsIgnoreCase(op)) {
                    return inferColumnAt(-2);
                }
            }

            // Symbol operators: walk back over operator symbols
            int rel = -1;
            boolean sawOp = false;
            while (true) {
                Token t = at(rel);
                if (t != null && t.type == TokenType.SYM && isOperatorSym(t.text)) {
                    sawOp = true;
                    rel--;
                    continue;
                }
                break;
            }
            if (sawOp) return inferColumnAt(rel);

            // last resort: scan backwards for first operator
            return scanBackwardForOperator();
        }

        private InferredColumnRef scanBackwardForOperator() {
            // find the nearest operator token and infer the column immediately to its left
            for (int rel = -1; rel >= -buf.length; rel--) {
                Token t = at(rel);
                if (t == null) continue;
                if (t.type == TokenType.SYM && isOperatorSym(t.text)) {
                    return inferColumnAt(rel - 1);
                }
                if (t.type == TokenType.WORD && isWordOperator(t.text)) {
                    return inferColumnAt(rel - 1);
                }
            }
            return null;
        }

        private static boolean isWordOperator(String s) {
            if (s == null) return false;
            return "LIKE".equalsIgnoreCase(s) || "IN".equalsIgnoreCase(s);
        }

        private static boolean isOperatorSym(String s) {
            if (s == null || s.isEmpty()) return false;
            return "=".equals(s) || ">".equals(s) || "<".equals(s) || "!".equals(s);
        }

        private InferredColumnRef inferColumnAt(int colRel) {
            Token tCol = at(colRel);
            if (tCol == null || tCol.type != TokenType.WORD) return null;

            Token tDot = at(colRel - 1);
            Token tAlias = at(colRel - 2);

            if (tDot != null && tDot.type == TokenType.SYM && ".".equals(tDot.text)
                    && tAlias != null && tAlias.type == TokenType.WORD) {
                return new InferredColumnRef(tAlias.text, tCol.text);
            }
            return new InferredColumnRef(null, tCol.text);
        }

        private Token at(int rel) {
            if (pos <= 0) return null;
            int tokenIndex = pos + rel;
            int oldest = pos - buf.length;
            if (tokenIndex < oldest || tokenIndex >= pos) return null;
            return buf[Math.floorMod(tokenIndex, buf.length)];
        }
    }

    private record InferredColumnRef(String alias, String column) {}

    // ==========================================================
    // Minimal SQL/XML scanner
    // ==========================================================

    private static final class SqlScan {
        private final String s;
        private int i = 0;

        SqlScan(String s) { this.s = s == null ? "" : s; }
        boolean hasNext() { return i < s.length(); }
        char peek() { return s.charAt(i); }
        char read() { return s.charAt(i++); }

        boolean peekIsLineComment() { return i + 1 < s.length() && s.charAt(i) == '-' && s.charAt(i + 1) == '-'; }
        String readLineComment() {
            int st = i;
            i += 2;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\n') break;
            }
            return s.substring(st, i);
        }

        boolean peekIsBlockComment() { return i + 1 < s.length() && s.charAt(i) == '/' && s.charAt(i + 1) == '*'; }
        String readBlockComment() {
            int st = i;
            i += 2;
            while (i + 1 < s.length()) {
                char c = s.charAt(i++);
                if (c == '*' && s.charAt(i) == '/') { i++; break; }
            }
            return s.substring(st, i);
        }

        boolean peekIsSingleQuotedString() { return s.charAt(i) == '\''; }
        String readSingleQuotedString() {
            int st = i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\'' && (i < 2 || s.charAt(i - 2) != '\\')) break;
            }
            return s.substring(st, i);
        }

        boolean peekIsDoubleQuotedString() { return s.charAt(i) == '"'; }
        String readDoubleQuotedString() {
            int st = i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"' && (i < 2 || s.charAt(i - 2) != '\\')) break;
            }
            return s.substring(st, i);
        }

        boolean peekIsCdata() { return i + 9 < s.length() && s.startsWith("<![CDATA[", i); }
        String readCdata() {
            int st = i;
            int end = s.indexOf("]]>", i + 9);
            if (end < 0) { i = s.length(); return s.substring(st); }
            i = end + 3;
            return s.substring(st, i);
        }

        boolean peekIsXmlTag() {
            if (i >= s.length()) return false;
            if (s.charAt(i) != '<') return false;
            if (i + 1 >= s.length()) return false;
            char n = s.charAt(i + 1);
            // MyBatis dynamic SQL may contain SQL operators like '<', '<=', '<>' outside CDATA.
            // Treat '<' as an XML tag only when the next char looks like a tag start.
            return (n == '/' || n == '!' || n == '?' || Character.isLetter(n));
        }
        String readXmlTag() {
            int st = i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '>') break;
            }
            return s.substring(st, i);
        }

        boolean peekIsMyBatisParam() {
            if (i + 1 >= s.length()) return false;
            char c1 = s.charAt(i);
            char c2 = s.charAt(i + 1);
            return (c1 == '#' || c1 == '$') && c2 == '{';
        }

        String readMyBatisParam() {
            int st = i;
            i += 2; // #{ or ${
            int depth = 1;
            while (i < s.length() && depth > 0) {
                char c = s.charAt(i++);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            return s.substring(st, i);
        }

        String readWord() {
            int st = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (!isWordStart(c)) break;
                i++;
            }
            return s.substring(st, i);
        }
    }

    private static final class CdataUtil {
        static String innerOf(String cdataRaw) {
            if (cdataRaw == null) return null;
            int st = cdataRaw.indexOf("<![CDATA[");
            if (st < 0) return null;
            int mid = st + "<![CDATA[".length();
            int ed = cdataRaw.lastIndexOf("]]>");
            if (ed < 0 || ed < mid) return null;
            return cdataRaw.substring(mid, ed);
        }

        static String wrapWithSameCdata(String raw, String newInner) {
            // Preserve exact <![CDATA[ prefix spacing if any (rare), and trailing ]]>
            int st = raw.indexOf("<![CDATA[");
            int ed = raw.lastIndexOf("]]>");
            if (st < 0 || ed < 0 || ed < st) return "<![CDATA[" + newInner + "]]>";
            String prefix = raw.substring(0, st + "<![CDATA[".length());
            String suffix = raw.substring(ed);
            return prefix + newInner + suffix;
        }
    }

    private static int findMatchingParen(String s, int openIdx) {
        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);

            if (!inDouble && c == '\'' && (i == 0 || s.charAt(i - 1) != '\\')) inSingle = !inSingle;
            if (!inSingle && c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inDouble = !inDouble;
            if (inSingle || inDouble) continue;

            if (i + 1 < s.length() && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) return -1;
                i = end + 1;
                continue;
            }
            if (i + 1 < s.length() && s.charAt(i) == '-' && s.charAt(i + 1) == '-') {
                int end = s.indexOf('\n', i + 2);
                if (end < 0) return -1;
                i = end;
                continue;
            }

            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static java.util.List<String> splitTopLevelComma(String s) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (s == null) return out;

        int depth = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        int last = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (!inDouble && c == '\'' && (i == 0 || s.charAt(i - 1) != '\\')) inSingle = !inSingle;
            if (!inSingle && c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inDouble = !inDouble;
            if (inSingle || inDouble) continue;

            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                out.add(s.substring(last, i).trim());
                last = i + 1;
            }
        }
        if (last <= s.length()) out.add(s.substring(last).trim());
        return out;
    }

    private static String normalizeColumnToken(String t) {
        if (t == null) return "";
        String x = t.trim();
        int dot = x.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < x.length()) x = x.substring(dot + 1);
        x = x.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
        int c1 = x.indexOf("/*");
        if (c1 >= 0) x = x.substring(0, c1).trim();
        return x;
    }

    private static java.util.List<String> extractMyBatisTokens(String expr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (expr == null) return out;

        for (int i = 0; i < expr.length() - 2; i++) {
            char c1 = expr.charAt(i);
            char c2 = expr.charAt(i + 1);
            if ((c1 == '#' || c1 == '$') && c2 == '{') {
                int end = expr.indexOf('}', i + 2);
                if (end > i) {
                    out.add(expr.substring(i, end + 1));
                    i = end;
                }
            }
        }
        return out;
    }
}
