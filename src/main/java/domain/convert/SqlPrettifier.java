package domain.convert;
/**
 * Very conservative SQL formatter.
 *
 * <p>This project intentionally avoids a full-blown SQL formatter at this stage
 * (KISS). The current implementation only normalizes newlines and trims trailing
 * spaces per line. It is safe to enable for logging/readability and should not
 * change semantics.</p>
 */
final class SqlPrettifier {

    private SqlPrettifier() {}

    static String format(String sql) {
        if (sql == null || sql.isEmpty()) return sql;

        // Normalize newlines.
        final String s0 = sql.replace("\r\n", "\n").replace("\r", "\n");

        // NOTE: This is intentionally conservative (no full SQL grammar). It does:
        // - trim trailing spaces
        // - normalize spaces between tokens
        // - add newlines before major clauses
        // - indent by parenthesis depth
        // - preserve strings/comments/MyBatis params/tags verbatim
        final SqlScan st = new SqlScan(s0);
        final StringBuilder out = new StringBuilder(s0.length() + 64);

        int depth = 0;
        boolean lineStart = true;
        boolean pendingSpace = false;

        while (st.hasNext()) {
            // Preserve tokens that must not be reformatted.
            if (st.peekIsLineComment()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readLineComment());
                lineStart = endsWithNewline(out);
                continue;
            }
            if (st.peekIsBlockComment()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readBlockComment());
                lineStart = endsWithNewline(out);
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readSingleQuotedString());
                lineStart = false;
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readDoubleQuotedString());
                lineStart = false;
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readMyBatisParam());
                lineStart = false;
                continue;
            }
            if (st.peekIsHashToken()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readHashToken());
                lineStart = false;
                continue;
            }
            if (st.peekIsCdata()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readCdata());
                lineStart = endsWithNewline(out);
                continue;
            }
            if (st.peekIsXmlTag()) {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(st.readXmlTag());
                lineStart = endsWithNewline(out);
                continue;
            }

            char c = st.peek();

            // Newlines: keep them, but trim trailing spaces before emitting.
            if (c == '\n') {
                rtrimLastLine(out);
                out.append('\n');
                st.read();
                lineStart = true;
                pendingSpace = false;
                continue;
            }

            // Whitespace: collapse to a single pending space.
            if (Character.isWhitespace(c)) {
                st.readWhileWhitespace();
                pendingSpace = !lineStart;
                continue;
            }

            // Parentheses adjust indentation depth.
            if (c == '(') {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append('(');
                st.read();
                depth++;
                lineStart = false;
                continue;
            }
            if (c == ')') {
                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append(')');
                st.read();
                depth = Math.max(0, depth - 1);
                lineStart = false;
                continue;
            }

            // Comma: keep tight, but allow space after.
            if (c == ',') {
                pendingSpace = false;
                out.append(',');
                st.read();
                lineStart = false;
                pendingSpace = true;
                continue;
            }

            // Words/keywords
            if (isWordStart(c)) {
                String word = st.readWord();
                String upper = word.toUpperCase(java.util.Locale.ROOT);

                // Try to merge common 2-word clauses.
                String merged = tryMergeClause(upper, st);
                String kw = merged != null ? merged : upper;

                if (isMajorClause(kw)) {
                    // Start clause on a new line unless we are already at line start.
                    if (!lineStart && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        rtrimLastLine(out);
                        out.append('\n');
                        appendIndent(out, depth);
                        lineStart = true;
                        pendingSpace = false;
                    } else if (lineStart) {
                        appendIndent(out, depth);
                        pendingSpace = false;
                    } else {
                        flushSpace(out, lineStart, pendingSpace);
                        pendingSpace = false;
                    }
                } else {
                    flushSpace(out, lineStart, pendingSpace);
                    pendingSpace = false;
                }

                // Emit the original word casing for non-keywords; for keywords use upper for readability.
                // If merged clause was used, it already includes space (e.g., "GROUP BY").
                if (merged != null) {
                    out.append(merged);
                } else {
                    out.append(isKeywordish(upper) ? upper : word);
                }
                lineStart = false;
                continue;
            }

            // Operators / other symbols: emit as-is.
            flushSpace(out, lineStart, pendingSpace);
            pendingSpace = false;
            out.append(c);
            st.read();
            lineStart = false;
        }

        // Final trim of trailing spaces on last line.
        rtrimLastLine(out);
        return out.toString();
    }

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n';
    }

    private static void flushSpace(StringBuilder out, boolean lineStart, boolean pendingSpace) {
        if (!lineStart && pendingSpace) out.append(' ');
    }

    private static void appendIndent(StringBuilder out, int depth) {
        int n = Math.max(0, depth) * 2;
        for (int i = 0; i < n; i++) out.append(' ');
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isKeywordish(String upper) {
        // A small set: if you want more, add them here.
        return isMajorClause(upper) || upper.equals("JOIN") || upper.equals("INNER") || upper.equals("LEFT")
                || upper.equals("RIGHT") || upper.equals("FULL") || upper.equals("CROSS") || upper.equals("ON")
                || upper.equals("AND") || upper.equals("OR") || upper.equals("IN") || upper.equals("AS")
                || upper.equals("DISTINCT") || upper.equals("CASE") || upper.equals("WHEN") || upper.equals("THEN")
                || upper.equals("ELSE") || upper.equals("END") || upper.equals("NULL") || upper.equals("IS");
    }

    private static boolean isMajorClause(String kw) {
        return kw.equals("SELECT")
                || kw.equals("FROM")
                || kw.equals("WHERE")
                || kw.equals("GROUP BY")
                || kw.equals("HAVING")
                || kw.equals("ORDER BY")
                || kw.equals("UNION")
                || kw.equals("UNION ALL")
                || kw.equals("INSERT")
                || kw.equals("UPDATE")
                || kw.equals("DELETE")
                || kw.equals("MERGE")
                || kw.equals("VALUES")
                || kw.equals("SET")
                || kw.equals("JOIN")
                || kw.endsWith(" JOIN")
                || kw.equals("ON");
    }

    private static String tryMergeClause(String upperFirst, SqlScan st) {
        // Lookahead to merge "GROUP BY" / "ORDER BY" / "UNION ALL" / "LEFT JOIN" etc.
        int save = st.pos;
        st.readWhileWhitespace();
        if (!st.hasNext() || !isWordStart(st.peek())) { st.pos = save; return null; }
        String second = st.readWord().toUpperCase(java.util.Locale.ROOT);
        String merged = null;

        if (upperFirst.equals("GROUP") && second.equals("BY")) merged = "GROUP BY";
        else if (upperFirst.equals("ORDER") && second.equals("BY")) merged = "ORDER BY";
        else if (upperFirst.equals("UNION") && second.equals("ALL")) merged = "UNION ALL";
        else if ((upperFirst.equals("LEFT") || upperFirst.equals("RIGHT") || upperFirst.equals("FULL")
                || upperFirst.equals("INNER") || upperFirst.equals("CROSS")) && second.equals("JOIN")) {
            merged = upperFirst + " JOIN";
        }

        if (merged == null) {
            st.pos = save;
        }
        return merged;
    }

    private static void rtrimLastLine(StringBuilder out) {
        int n = out.length();
        int i = n - 1;
        while (i >= 0) {
            char c = out.charAt(i);
            if (c == '\n') break;
            if (c != ' ' && c != '\t') break;
            i--;
        }
        // Remove trailing spaces/tabs until end of line.
        int cut = i;
        // If we broke on space/tab, keep it; otherwise cut after it.
        while (cut + 1 < out.length()) {
            char c = out.charAt(cut + 1);
            if (c == ' ' || c == '\t') {
                out.deleteCharAt(cut + 1);
            } else {
                break;
            }
        }
    }

    private static String rtrim(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        int end = s.length() - 1;
        while (end >= 0) {
            char c = s.charAt(end);
            if (c != ' ' && c != '\t') break;
            end--;
        }
        return (end < 0) ? "" : s.substring(0, end + 1);
    }
}
