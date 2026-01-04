package domain.convert;

import java.util.Locale;

/**
 * SQL formatter (KISS, no full grammar).
 *
 * <p>Goals:
 * <ul>
 *   <li>Stable indentation for SELECT / INSERT / UPDATE / DELETE / MERGE (including INSERT-SELECT)</li>
 *   <li>Do not break MyBatis dynamic tags and params (treat as atomic tokens)</li>
 *   <li>Keep comments readable and align trailing block-comments conservatively</li>
 * </ul>
 *
 * <p>This formatter is intentionally less aggressive than full SQL prettifiers,
 * but it is clause-aware and list-aware (SELECT list, SET list, INSERT columns/values).</p>
 */
final class SqlPrettifier {

    private SqlPrettifier() {
    }

    static String format(String sql) {
        // V2 프리티파이어(From/Join/Where/Set/Values/...) 우선 적용
        return SqlFormatterV2.format(sql);
    }

    /**
     * (레거시) 기존 V1 로직. 필요 시 회귀 비교용으로 남겨둔다.
     */
    static String formatV1(String sql) {
        if (sql == null || sql.isEmpty()) return sql;

        // Normalize newlines.
        final String s0 = sql.replace("\r\n", "\n")
                .replace("\r", "\n");

        final SqlScan st = new SqlScan(s0);
        final StringBuilder out = new StringBuilder(s0.length() + 128);

        int depth = 0;

        boolean lineStart = true;
        boolean pendingSpace = false;

        boolean listLeadingCommaPending = false;
        boolean suppressNextOriginalNewline = false;

        // Clause states (top-level aware by depth)
        boolean inSelectList = false;
        int selectDepth = -1;

        boolean inFrom = false;
        int fromDepth = -1;

        boolean inWhere = false;
        int whereDepth = -1;

        boolean inOn = false;
        int onDepth = -1;

        boolean inSetList = false;
        int setDepth = -1;

        boolean valuesPendingParen = false;
        boolean inValuesList = false;
        int valuesDepth = -1;

        boolean insertColumnsPendingParen = false;
        boolean inInsertColumns = false;
        int insertColumnsDepth = -1;

        boolean afterMajorKeywordNewline = false;

        while (st.hasNext()) {

            // ------------------------------------------------------------
            // Preserve tokens that must not be reformatted.
            // ------------------------------------------------------------
            if (st.peekIsLineComment()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readLineComment());
                lineStart = endsWithNewline(out);
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsBlockComment()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readBlockComment());
                lineStart = endsWithNewline(out);
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readSingleQuotedString());
                lineStart = false;
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readDoubleQuotedString());
                lineStart = false;
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readMyBatisParam());
                lineStart = false;
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsHashToken()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readHashToken());
                lineStart = false;
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsCdata()) {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                String raw = st.readCdata();
                out.append(CdataUtil.transform(raw, SqlPrettifier::format));
                lineStart = endsWithNewline(out);
                afterMajorKeywordNewline = false;
                continue;
            }
            if (st.peekIsXmlTag()) {
                // MyBatis dynamic tags are treated as atomic tokens.
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append(st.readXmlTag());
                lineStart = endsWithNewline(out);
                afterMajorKeywordNewline = false;
                continue;
            }

            char c = st.peek();

            // ------------------------------------------------------------
            // Newlines
            // ------------------------------------------------------------
            if (c == '\n') {
                st.read();
                if (suppressNextOriginalNewline) {
                    suppressNextOriginalNewline = false;
                    // We already emitted a newline (e.g., after list-separator).
                    continue;
                }
                rtrimLastLine(out);
                if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                lineStart = true;
                pendingSpace = false;
                afterMajorKeywordNewline = false;
                continue;
            }

            // Whitespace collapse
            if (Character.isWhitespace(c)) {
                st.readWhileWhitespace();
                pendingSpace = !lineStart;
                continue;
            }

            // If a major keyword requested a newline after it, do it once before emitting next token.
            if (afterMajorKeywordNewline) {
                if (!lineStart) {
                    rtrimLastLine(out);
                    out.append('\n');
                    lineStart = true;
                    pendingSpace = false;
                }
                afterMajorKeywordNewline = false;
            }

            // ------------------------------------------------------------
            // Parentheses
            // ------------------------------------------------------------
            if (c == '(') {
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;
                out.append('(');
                st.read();
                depth++;

                if (insertColumnsPendingParen) {
                    insertColumnsPendingParen = false;
                    inInsertColumns = true;
                    insertColumnsDepth = depth;
                    // new line after opening paren for readability
                    out.append('\n');
                    lineStart = true;
                    pendingSpace = false;
                }
                if (valuesPendingParen) {
                    valuesPendingParen = false;
                    inValuesList = true;
                    valuesDepth = depth;
                    out.append('\n');
                    lineStart = true;
                    pendingSpace = false;
                }

                continue;
            }
            if (c == ')') {
                // close list context if this paren belongs to it
                if (inInsertColumns && depth == insertColumnsDepth) {
                    inInsertColumns = false;
                    insertColumnsDepth = -1;
                }
                if (inValuesList && depth == valuesDepth) {
                    inValuesList = false;
                    valuesDepth = -1;
                }
                if (lineStart) appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;

                // If we are at line start inside a list, keep ')' aligned with its parent.
                if (lineStart && out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                    // already indented by appendIndentForContext
                }

                if (listLeadingCommaPending) listLeadingCommaPending = false;

                out.append(')');
                st.read();
                depth = Math.max(0, depth - 1);
                lineStart = false;
                continue;
            }

            // ------------------------------------------------------------
            // Comma handling (list-aware)
            // ------------------------------------------------------------
            if (c == ',') {
                out.append(',');
                st.read();

                boolean breakLine = inSelectList && depth == selectDepth;
                if (inInsertColumns && depth == insertColumnsDepth) breakLine = true;
                if (inValuesList && depth == valuesDepth) breakLine = true;
                if (inSetList && depth == setDepth) breakLine = true;

                st.read();

                if (breakLine) {
                    // "leading comma" style: next item line starts with ", "
                    rtrimLastLine(out);
                    if (out.length() == 0 || out.charAt(out.length() - 1) != '\n') out.append('\n');
                    lineStart = true;
                    pendingSpace = false;
                    listLeadingCommaPending = true;
                    suppressNextOriginalNewline = true;
                } else {
                    out.append(',');
                    lineStart = false;
                    pendingSpace = true;
                }
                continue;
            }

            // ------------------------------------------------------------
            // Words / Keywords
            // ------------------------------------------------------------
            if (isWordStart(c)) {
                String word = st.readWord();
                String upper = word.toUpperCase(Locale.ROOT);

                // Try to merge common 2-word clauses.
                String merged = tryMergeClause(upper, st);
                String kw = merged != null ? merged : upper;

                // Major clauses start on new line.
                if (isMajorClause(kw)) {
                    // list separator must not leak into a clause keyword (FROM/WHERE/...)
                    listLeadingCommaPending = false;
                    if (!lineStart && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                        rtrimLastLine(out);
                        out.append('\n');
                        lineStart = true;
                    }

                    if (lineStart) {
                        appendIndent(out, Math.max(0, depth) * 2);
                    }
                    pendingSpace = false;

                    // switch clause states
                    // (reset some state unless entering nested blocks)
                    if (kw.equals("SELECT")) {
                        inSelectList = true;
                        selectDepth = depth;
                        inFrom = false;
                        inWhere = false;
                        inOn = false;
                        inSetList = false;
                        afterMajorKeywordNewline = true;
                    } else if (kw.equals("FROM")) {
                        inFrom = true;
                        fromDepth = depth;
                        inSelectList = false;
                        inWhere = false;
                        inOn = false;
                        afterMajorKeywordNewline = true;
                    } else if (kw.equals("WHERE")) {
                        inWhere = true;
                        whereDepth = depth;
                        inOn = false;
                        afterMajorKeywordNewline = true;
                    } else if (kw.equals("ON")) {
                        inOn = true;
                        onDepth = depth;
                        afterMajorKeywordNewline = true;
                    } else if (kw.equals("SET")) {
                        inSetList = true;
                        setDepth = depth;
                        afterMajorKeywordNewline = true;
                    } else if (kw.equals("VALUES")) {
                        valuesPendingParen = true;
                        afterMajorKeywordNewline = false;
                    } else if (kw.equals("INSERT")) {
                        // keep the rest; INSERT INTO is merged to clause
                    } else if (kw.equals("UPDATE") || kw.equals("DELETE") || kw.equals("MERGE")) {
                        // reset minor contexts
                        inSelectList = false;
                        inFrom = false;
                        inWhere = false;
                        inOn = false;
                        inSetList = false;
                    } else if (kw.endsWith(" JOIN") || kw.equals("JOIN")) {
                        inOn = false;
                        // keep FROM context
                        afterMajorKeywordNewline = false;
                    } else if (kw.equals("GROUP BY") || kw.equals("ORDER BY") || kw.equals("HAVING")) {
                        inSelectList = false;
                        inFrom = false;
                        inWhere = false;
                        inOn = false;
                        afterMajorKeywordNewline = true;
                    }

                    out.append(kw);
                    lineStart = false;
                    continue;
                }

                // Break AND/OR in WHERE/ON (top-level for that clause depth)
                if ((upper.equals("AND") || upper.equals("OR"))
                        && ((inWhere && depth == whereDepth) || (inOn && depth == onDepth))) {
                    rtrimLastLine(out);
                    out.append('\n');
                    lineStart = true;
                    pendingSpace = false;
                    appendIndentForContext(out, depth,
                            inSelectList, selectDepth,
                            inFrom, fromDepth,
                            inWhere, whereDepth,
                            inOn, onDepth,
                            inSetList, setDepth,
                            inInsertColumns, insertColumnsDepth,
                            inValuesList, valuesDepth);
                    out.append(upper);
                    lineStart = false;
                    pendingSpace = true;
                    continue;
                }

                // Heuristic: INSERT column list begins at first '(' after INSERT INTO <table>
                if ((upper.equals("INTO") || (merged != null && merged.equals("INSERT INTO")))) {
                    // we don't enforce newline here; just mark that the next '(' likely starts column list.
                    insertColumnsPendingParen = true;
                }

                if (lineStart) {
                    appendIndentForContext(out, depth,
                            inSelectList, selectDepth,
                            inFrom, fromDepth,
                            inWhere, whereDepth,
                            inOn, onDepth,
                            inSetList, setDepth,
                            inInsertColumns, insertColumnsDepth,
                            inValuesList, valuesDepth);
                }
                flushSpace(out, lineStart, pendingSpace);
                if (lineStart && listLeadingCommaPending) {
                    out.append(", ");
                    listLeadingCommaPending = false;
                    lineStart = false;
                }
                pendingSpace = false;

                out.append(isKeywordish(upper) ? upper : word);
                lineStart = false;
                continue;
            }

            // Operators / other symbols
            if (lineStart) {
                appendIndentForContext(out, depth,
                        inSelectList, selectDepth,
                        inFrom, fromDepth,
                        inWhere, whereDepth,
                        inOn, onDepth,
                        inSetList, setDepth,
                        inInsertColumns, insertColumnsDepth,
                        inValuesList, valuesDepth);
            }
            flushSpace(out, lineStart, pendingSpace);
            if (lineStart && listLeadingCommaPending) {
                out.append(", ");
                listLeadingCommaPending = false;
                lineStart = false;
            }
            pendingSpace = false;
            out.append(c);
            st.read();
            lineStart = false;
        }

        rtrimLastLine(out);

        // Trailing block-comment alignment (conservative).
        // trailing inline block comment 정렬 규칙:
        // - 40자 이내는 42열에 맞춰 정렬
        // - 40자 초과는 한 칸만 띄우고 주석
        return InlineBlockCommentAligner.alignTrailingBlockComments(out.toString(), 40, 42);
    }

    private static boolean endsWithNewline(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n';
    }

    private static void flushSpace(StringBuilder out, boolean lineStart, boolean pendingSpace) {
        if (!lineStart && pendingSpace) out.append(' ');
    }

    private static void appendIndent(StringBuilder out, int spaces) {
        for (int i = 0; i < spaces; i++) out.append(' ');
    }

    private static void appendIndentForContext(StringBuilder out,
                                               int depth,
                                               boolean inSelectList, int selectDepth,
                                               boolean inFrom, int fromDepth,
                                               boolean inWhere, int whereDepth,
                                               boolean inOn, int onDepth,
                                               boolean inSetList, int setDepth,
                                               boolean inInsertColumns, int insertColumnsDepth,
                                               boolean inValuesList, int valuesDepth) {
        int base = Math.max(0, depth) * 2;

        // SELECT list items are at selectDepth (same depth), but should be indented one level deeper.
        if (inSelectList && depth == selectDepth) {
            appendIndent(out, (selectDepth + 1) * 2);
            return;
        }

        if (inFrom && depth == fromDepth) {
            appendIndent(out, (fromDepth + 1) * 2);
            return;
        }

        if (inWhere && depth == whereDepth) {
            appendIndent(out, (whereDepth + 1) * 2);
            return;
        }

        if (inOn && depth == onDepth) {
            appendIndent(out, (onDepth + 1) * 2);
            return;
        }

        if (inSetList && depth == setDepth) {
            appendIndent(out, (setDepth + 1) * 2);
            return;
        }

        if (inInsertColumns && depth == insertColumnsDepth) {
            appendIndent(out, insertColumnsDepth * 2);
            return;
        }

        if (inValuesList && depth == valuesDepth) {
            appendIndent(out, valuesDepth * 2);
            return;
        }

        appendIndent(out, base);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || Character.isDigit(c) || c == '_' || c == '$';
    }

    private static boolean isKeywordish(String upper) {
        return isMajorClause(upper)
                || upper.equals("JOIN") || upper.equals("INNER") || upper.equals("LEFT")
                || upper.equals("RIGHT") || upper.equals("FULL") || upper.equals("CROSS")
                || upper.equals("ON")
                || upper.equals("AND") || upper.equals("OR") || upper.equals("IN")
                || upper.equals("AS") || upper.equals("DISTINCT")
                || upper.equals("CASE") || upper.equals("WHEN") || upper.equals("THEN")
                || upper.equals("ELSE") || upper.equals("END")
                || upper.equals("NULL") || upper.equals("IS")
                || upper.equals("INTO") || upper.equals("USING")
                || upper.equals("WHEN") || upper.equals("MATCHED") || upper.equals("NOT")
                || upper.equals("THEN") || upper.equals("VALUES");
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
                || kw.equals("INSERT INTO")
                || kw.equals("UPDATE")
                || kw.equals("DELETE")
                || kw.equals("DELETE FROM")
                || kw.equals("MERGE")
                || kw.equals("MERGE INTO")
                || kw.equals("VALUES")
                || kw.equals("SET")
                || kw.equals("JOIN")
                || kw.endsWith(" JOIN")
                || kw.equals("ON")
                || kw.equals("USING")
                || kw.equals("WHEN MATCHED")
                || kw.equals("WHEN NOT")
                || kw.equals("THEN");
    }

    private static String tryMergeClause(String upperFirst, SqlScan st) {
        int save = st.pos;
        st.readWhileWhitespace();
        if (!st.hasNext() || !isWordStart(st.peek())) {
            st.pos = save;
            return null;
        }
        String second = st.readWord()
                .toUpperCase(Locale.ROOT);

        String merged = null;
        if (upperFirst.equals("GROUP") && second.equals("BY")) merged = "GROUP BY";
        else if (upperFirst.equals("ORDER") && second.equals("BY")) merged = "ORDER BY";
        else if (upperFirst.equals("UNION") && second.equals("ALL")) merged = "UNION ALL";
        else if (upperFirst.equals("INSERT") && second.equals("INTO")) merged = "INSERT INTO";
        else if (upperFirst.equals("DELETE") && second.equals("FROM")) merged = "DELETE FROM";
        else if (upperFirst.equals("MERGE") && second.equals("INTO")) merged = "MERGE INTO";
        else if (upperFirst.equals("WHEN") && second.equals("MATCHED")) merged = "WHEN MATCHED";
        else if (upperFirst.equals("WHEN") && second.equals("NOT")) merged = "WHEN NOT";
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
        int i = out.length() - 1;
        while (i >= 0) {
            char c = out.charAt(i);
            if (c == '\n') break;
            if (c != ' ' && c != '\t') break;
            i--;
        }
        int cut = i;
        while (cut + 1 < out.length()) {
            char c = out.charAt(cut + 1);
            if (c == ' ' || c == '\t') out.deleteCharAt(cut + 1);
            else break;
        }
    }
}
