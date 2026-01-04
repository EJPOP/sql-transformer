// === SqlFormatterV2.java ===
// (사용자가 업로드한 최종본 기준으로 수정 반영본)
// - CDATA 내부 shift 적용
// - 서브쿼리 괄호( (SELECT|WITH ) 진입/탈출 시 부모 ctx 복구

package domain.convert;

import java.util.Collections;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/**
 * sql-formatter류 스타일에 가깝게 정렬하는 프리티파이어(V2).
 *
 * <p>핵심 목표</p>
 * <ul>
 *   <li>SELECT 첫 컬럼은 SELECT와 같은 라인</li>
 *   <li>FROM/WHERE/SET/USING/ON/VALUES 절은 첫 항목을 같은 라인에 출력</li>
 *   <li>JOIN은 2칸 들여쓰기, ON은 4칸 들여쓰기</li>
 *   <li>WHERE의 AND/OR는 2칸 들여쓰기 + AND/OR 정렬</li>
 *   <li>MyBatis 동적 태그/CDATA/주석/문자열/#{...}는 깨지지 않게 보존</li>
 * </ul>
 */
final class SqlFormatterV2 {

    private static final int CLAUSE_PAD_COL = 6; // e.g. FROM___, WHERE__, SET____
    private static final Set<String> KEYWORDS = keywordSet();

    private SqlFormatterV2() {
    }

    static String format(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        // CDATA 내부만 재귀 적용
        if (CdataUtil.isCdata(sql)) {
            return CdataUtil.transform(sql, SqlFormatterV2::format);
        }

        SqlScan st = new SqlScan(sql);
        StringBuilder out = new StringBuilder(sql.length() + 128);

        int depth = 0;
        boolean lineStart = true;
        boolean pendingSpace = false;

        // list contexts
        Ctx ctx = Ctx.NONE;
        int ctxDepth = 0;
        int baseIndentAtCtx = 0;
        int listCommaIndentAtCtx = 0; // comma 위치(leading comma) 정렬용

        boolean mergeMode = false;

        // CASE expression blocks (common inside SELECT lists).
        // Align WHEN/ELSE with 2-space indent relative to CASE column,
        // and END aligned to CASE column.
        final Deque<CaseBlock> caseStack = new ArrayDeque<>();

        // paren list contexts (INSERT columns / VALUES list)
        boolean inParenList = false;
        int parenListDepth = -1; // depth after '(' consumed
        int parenListIndent = 0;
        boolean parenListFirstItemEmitted = false;

        boolean insertColumnsPendingParen = false;
        boolean valuesPendingParen = false;

        // scalar subquery paren blocks: keep base column of '(' and restore outer ctx after closing ')'
        final Deque<ParenBlock> parenSubqueryStack = new ArrayDeque<>();

        while (st.hasNext()) {
            char c = st.peek();

            // ------------------------------------------------------------
            // Atomic tokens
            // ------------------------------------------------------------
            if (st.peekIsCdata()) {
                String raw = st.readCdata();

                // CDATA is usually a SQL fragment (e.g., SELECT list items) inside MyBatis mappers.
                // Formatting it standalone would left-align at column 0 and break the surrounding indentation.
                final int cdataShift = computeCdataInnerShift(
                        ctx, depth, ctxDepth, baseIndentAtCtx, listCommaIndentAtCtx,
                        inParenList, parenListDepth, parenListIndent,
                        parenSubqueryStack
                );

                String converted = CdataUtil.transform(raw, inner -> {
                    String formatted = SqlFormatterV2.format(inner);
                    return indentNonEmptyLines(formatted, cdataShift);
                });

                flushSpace(out, lineStart, pendingSpace);
                out.append(converted);
                lineStart = endsWithNewline(out);
                pendingSpace = false;
                continue;
            }

            if (st.peekIsXmlTag()) {
                String raw = st.readXmlTag();
                // 태그는 줄바꿈 구조를 최대한 보존한다. (태그 앞이 공백이면 제거)
                if (!lineStart) {
                    rtrimLastLine(out);
                    out.append('\n');
                    lineStart = true;
                }
                appendIndent(out, indentFor(depth, parenSubqueryStack));
                out.append(raw.trim());
                out.append('\n');
                lineStart = true;
                pendingSpace = false;
                continue;
            }

            if (st.peekIsLineComment()) {
                String com = st.readLineComment();
                // 라인 주석은 현재 라인에서 그대로 유지
                flushSpace(out, lineStart, pendingSpace);
                out.append(com);
                lineStart = endsWithNewline(out);
                pendingSpace = false;
                continue;
            }
            if (st.peekIsBlockComment()) {
                String com = st.readBlockComment();
                flushSpace(out, lineStart, pendingSpace);
                out.append(com);
                lineStart = endsWithNewline(out);
                pendingSpace = true;
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                String str = st.readSingleQuotedString();
                flushSpace(out, lineStart, pendingSpace);
                out.append(str);
                lineStart = false;
                pendingSpace = false;
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                String str = st.readDoubleQuotedString();
                flushSpace(out, lineStart, pendingSpace);
                out.append(str);
                lineStart = false;
                pendingSpace = false;
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                String p = st.readMyBatisParam();
                flushSpace(out, lineStart, pendingSpace);
                out.append(p);
                lineStart = false;
                pendingSpace = false;
                continue;
            }
            if (st.peekIsHashToken()) {
                String t = st.readHashToken();
                flushSpace(out, lineStart, pendingSpace);
                out.append(t);
                lineStart = false;
                pendingSpace = false;
                continue;
            }

            // ------------------------------------------------------------
            // Whitespace
            // ------------------------------------------------------------
            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    // 기존 개행은 최대한 하나로 정리
                    rtrimLastLine(out);
                    out.append('\n');
                    lineStart = true;
                    pendingSpace = false;
                } else {
                    pendingSpace = !lineStart;
                }
                st.read();
                continue;
            }

            // ------------------------------------------------------------
            // Parentheses
            // ------------------------------------------------------------
            if (c == '(') {
                boolean startsSubqueryBlock = st.peekParenStartsWithSelectOrWith();

                if (lineStart) {
                    if (inParenList && depth == parenListDepth) {
                        appendIndent(out, parenListIndent);
                        parenListFirstItemEmitted = true;
                    } else {
                        appendIndent(out, indentFor(depth, parenSubqueryStack));
                    }
                    lineStart = false;
                }

                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;
                out.append('(');
                st.read();
                int openParenCol = currentColumn(out) - 1;
                depth++;

                if (startsSubqueryBlock) {
                    // save outer ctx so the nested SELECT does not overwrite parent's list context
                    parenSubqueryStack.push(new ParenBlock(depth, openParenCol, ctx, ctxDepth, baseIndentAtCtx, listCommaIndentAtCtx));

                    // newline after '(' to make subquery readable
                    out.append('\n');
                    lineStart = true;
                }

                if (insertColumnsPendingParen || valuesPendingParen) {
                    insertColumnsPendingParen = false;
                    valuesPendingParen = false;

                    inParenList = true;
                    parenListDepth = depth;
                    parenListIndent = (depth - 1) * 2 + 2;
                    parenListFirstItemEmitted = false;

                    out.append('\n');
                    lineStart = true;
                }
                continue;
            }
            if (c == ')') {
                // close subquery paren block
                if (!parenSubqueryStack.isEmpty() && depth == parenSubqueryStack.peek().depth) {
                    ParenBlock pb = parenSubqueryStack.pop();
                    // restore outer formatting context (SELECT list / SET list / etc.)
                    ctx = pb.savedCtx;
                    ctxDepth = pb.savedCtxDepth;
                    baseIndentAtCtx = pb.savedBaseIndentAtCtx;
                    listCommaIndentAtCtx = pb.savedListCommaIndentAtCtx;

                    rtrimLastLine(out);
                    out.append('\n');
                    appendIndent(out, pb.baseColumn);
                    lineStart = false;
                    pendingSpace = false;
                }

                // close paren list
                if (inParenList && depth == parenListDepth) {
                    rtrimLastLine(out);
                    out.append('\n');
                    appendIndent(out, (depth - 1) * 2);
                    inParenList = false;
                    parenListDepth = -1;
                    parenListIndent = 0;
                    parenListFirstItemEmitted = false;
                    lineStart = false;
                    pendingSpace = false;
                } else {
                    flushSpace(out, lineStart, pendingSpace);
                    pendingSpace = false;
                }

                out.append(')');
                st.read();
                depth = Math.max(0, depth - 1);
                lineStart = false;
                continue;
            }

            // ------------------------------------------------------------
            // Comma handling
            // ------------------------------------------------------------
            if (c == ',') {
                st.read();

                // SELECT/SET/GROUP BY/ORDER BY 리스트는 leading comma 스타일
                if ((ctx == Ctx.SELECT_LIST || ctx == Ctx.SET_LIST || ctx == Ctx.GROUP_LIST || ctx == Ctx.ORDER_LIST)
                        && depth == ctxDepth) {
                    rtrimLastLine(out);
                    out.append('\n');
                    appendIndent(out, listCommaIndentAtCtx);
                    out.append(", ");
                    lineStart = false;
                    pendingSpace = false;
                    st.readSpaces();
                    continue;
                }

                // INSERT columns / VALUES list는 괄호 내부에서 2칸 들여쓰기 + leading comma
                if (inParenList && depth == parenListDepth) {
                    rtrimLastLine(out);
                    out.append('\n');
                    appendIndent(out, parenListIndent);
                    out.append(", ");
                    lineStart = false;
                    pendingSpace = false;
                    st.readSpaces();
                    continue;
                }

                // 기타는 기본
                out.append(',');
                pendingSpace = true;
                lineStart = false;
                continue;
            }

            // ------------------------------------------------------------
            // Words / Keywords
            // ------------------------------------------------------------
            if (isWordStart(c)) {
                String word = st.readWord();
                String upper = word.toUpperCase(Locale.ROOT);

                // merge common 2-word/3-word clauses
                String merged = tryMergeClause(upper, st);
                String kw = merged != null ? merged : upper;

                // --- major clauses ---
                if (kw.equals("SELECT")) {
                    startClauseLine(out, indentFor(depth, parenSubqueryStack), "SELECT", false, lineStart);
                    out.append(' ');
                    st.readSpaces();
                    pendingSpace = false;

                    ctx = Ctx.SELECT_LIST;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);
                    listCommaIndentAtCtx = baseIndentAtCtx + 5; // leading comma 위치

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("FROM")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append("FROM");
                    appendPadSpaces(out, "FROM");

                    ctx = Ctx.FROM;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("WHERE")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append("WHERE");
                    appendPadSpaces(out, "WHERE");

                    ctx = Ctx.WHERE;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("HAVING")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append("HAVING");
                    appendPadSpaces(out, "HAVING");

                    ctx = Ctx.HAVING;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("GROUP BY")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    rtrimLastLine(out);
                    out.append("GROUP BY");

                    ctx = Ctx.GROUP_LIST;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);
                    listCommaIndentAtCtx = baseIndentAtCtx + ("GROUP BY".length() - 1);

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("ORDER BY")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    rtrimLastLine(out);
                    out.append("ORDER BY ");

                    ctx = Ctx.ORDER_LIST;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);
                    listCommaIndentAtCtx = baseIndentAtCtx + ("ORDER BY".length() - 1);

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("SET")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append("SET");
                    appendPadSpaces(out, "SET");

                    ctx = Ctx.SET_LIST;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);
                    listCommaIndentAtCtx = baseIndentAtCtx + 5;

                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("VALUES")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append("VALUES");
                    out.append(' ');
                    valuesPendingParen = true;

                    ctx = Ctx.NONE;
                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("UPDATE") || kw.equals("DELETE") || kw.equals("INSERT") || kw.equals("MERGE")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append(kw);
                    lineStart = false;
                    pendingSpace = true;
                    ctx = Ctx.NONE;

                    mergeMode = kw.equals("MERGE");
                    continue;
                }

                if (kw.equals("INSERT INTO") || kw.equals("DELETE FROM")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append(kw);
                    lineStart = false;
                    pendingSpace = true;
                    if (kw.equals("INSERT INTO")) insertColumnsPendingParen = true;
                    ctx = Ctx.NONE;
                    continue;
                }

                if (kw.endsWith(" JOIN") || kw.equals("JOIN")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack) + 2);
                    out.append(kw);
                    lineStart = false;
                    pendingSpace = true;
                    ctx = Ctx.FROM;
                    continue;
                }

                if (kw.equals("ON")) {
                    if (mergeMode) {
                        newlineIfNeeded(out, lineStart);
                        appendIndent(out, indentFor(depth, parenSubqueryStack));
                        out.append("ON");
                        appendPadSpaces(out, "ON");
                        ctx = Ctx.MERGE_ON;
                        ctxDepth = depth;
                        baseIndentAtCtx = indentFor(depth, parenSubqueryStack);
                        lineStart = false;
                        pendingSpace = false;
                        continue;
                    }

                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack) + 4);
                    out.append("ON ");
                    ctx = Ctx.JOIN_ON;
                    ctxDepth = depth;
                    baseIndentAtCtx = indentFor(depth, parenSubqueryStack);
                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                if (kw.equals("USING")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append("USING");
                    appendPadSpaces(out, "USING");
                    ctx = Ctx.FROM;
                    lineStart = false;
                    pendingSpace = false;
                    continue;
                }

                // WHEN MATCHED / WHEN NOT MATCHED
                if (kw.equals("WHEN MATCHED") || kw.equals("WHEN NOT MATCHED") || kw.equals("WHEN NOT")) {
                    newlineIfNeeded(out, lineStart);
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    out.append(kw.replace("WHEN NOT", "WHEN NOT"));
                    pendingSpace = true;
                    lineStart = false;
                    ctx = Ctx.NONE;
                    continue;
                }

                // AND/OR line breaks (WHERE/ON)
                if ((upper.equals("AND") || upper.equals("OR")) && depth == ctxDepth) {
                    if (ctx == Ctx.WHERE || ctx == Ctx.HAVING || ctx == Ctx.MERGE_ON) {
                        rtrimLastLine(out);
                        out.append('\n');
                        appendIndent(out, baseIndentAtCtx + 2);
                        out.append(upper);
                        out.append(" ");
                        lineStart = false;
                        pendingSpace = false;
                        continue;
                    }
                    if (ctx == Ctx.JOIN_ON) {
                        rtrimLastLine(out);
                        out.append('\n');
                        appendIndent(out, baseIndentAtCtx + 4);
                        out.append(upper);
                        out.append(' ');
                        lineStart = false;
                        pendingSpace = false;
                        continue;
                    }
                }

                // ------------------------------------------------------------
                // CASE blocks (mainly for SELECT list readability)
                // ------------------------------------------------------------
                if (!caseStack.isEmpty() && depth == caseStack.peek().depth) {
                    CaseBlock cb = caseStack.peek();

                    if (upper.equals("WHEN")) {
                        if (!lineStart) {
                            rtrimLastLine(out);
                            out.append('\n');
                            lineStart = true;
                        }
                        appendIndent(out, cb.baseColumn + 2);
                        out.append("WHEN ");
                        st.readSpaces();
                        lineStart = false;
                        pendingSpace = false;
                        continue;
                    }

                    if (upper.equals("ELSE")) {
                        if (!lineStart) {
                            rtrimLastLine(out);
                            out.append('\n');
                            lineStart = true;
                        }
                        appendIndent(out, cb.baseColumn + 2);
                        out.append("ELSE ");
                        st.readSpaces();
                        lineStart = false;
                        pendingSpace = false;
                        continue;
                    }

                    if (upper.equals("END")) {
                        if (!lineStart) {
                            rtrimLastLine(out);
                            out.append('\n');
                            lineStart = true;
                        }
                        appendIndent(out, cb.baseColumn);
                        out.append("END");
                        caseStack.pop();
                        st.readSpaces();
                        lineStart = false;
                        pendingSpace = true;
                        continue;
                    }

                    if (upper.equals("THEN")) {
                        if (lineStart) {
                            appendIndent(out, cb.baseColumn + 2);
                            lineStart = false;
                        }
                        flushSpace(out, lineStart, pendingSpace);
                        pendingSpace = false;
                        out.append("THEN ");
                        st.readSpaces();
                        lineStart = false;
                        pendingSpace = false;
                        continue;
                    }
                }

                if (upper.equals("CASE")) {
                    if (lineStart) {
                        if (inParenList && depth == parenListDepth) {
                            appendIndent(out, parenListIndent);
                            parenListFirstItemEmitted = true;
                            lineStart = false;
                        } else {
                            appendIndent(out, indentFor(depth, parenSubqueryStack));
                            lineStart = false;
                        }
                    }
                    flushSpace(out, lineStart, pendingSpace);
                    pendingSpace = false;

                    int col = currentColumn(out);
                    out.append("CASE");
                    caseStack.push(new CaseBlock(depth, col));

                    st.readSpaces();
                    lineStart = false;
                    pendingSpace = true;
                    continue;
                }

                // INSERT INTO 이후 '(' 추정
                if (upper.equals("INTO") && (lastKeywordLooksLikeInsertInto(out))) {
                    insertColumnsPendingParen = true;
                }

                // DEFAULT word output
                if (lineStart) {
                    if (inParenList && depth == parenListDepth) {
                        appendIndent(out, parenListIndent);
                        parenListFirstItemEmitted = true;
                        lineStart = false;
                    } else {
                        appendIndent(out, indentFor(depth, parenSubqueryStack));
                        lineStart = false;
                    }
                }

                flushSpace(out, lineStart, pendingSpace);
                pendingSpace = false;

                out.append(isKeyword(upper) ? upper : word);
                lineStart = false;

                continue;
            }

            // ------------------------------------------------------------
            // Operators / symbols
            // ------------------------------------------------------------
            if (lineStart) {
                if (inParenList && depth == parenListDepth) {
                    appendIndent(out, parenListIndent);
                    parenListFirstItemEmitted = true;
                    lineStart = false;
                } else {
                    appendIndent(out, indentFor(depth, parenSubqueryStack));
                    lineStart = false;
                }
            }
            flushSpace(out, lineStart, pendingSpace);
            pendingSpace = false;
            out.append(c);
            st.read();
            lineStart = false;
        }

        rtrimLastLine(out);

        // trailing block-comment 정렬: 40자 이내는 42열
        return InlineBlockCommentAligner.alignTrailingBlockComments(out.toString(), 40, 42);
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private static void newlineIfNeeded(StringBuilder out, boolean lineStart) {
        if (!lineStart) {
            rtrimLastLine(out);
            out.append('\n');
        }
    }

    private static void startClauseLine(StringBuilder out, int indent, String keyword, boolean pad, boolean lineStart) {
        newlineIfNeeded(out, lineStart);
        rtrimLastLine(out);      // 헤더 앞뒤 공백 찌꺼기 제거
        appendIndent(out, indent);
        out.append(keyword);
        if (pad) appendPadSpaces(out, keyword);
    }

    /**
     * Base indent for current depth.
     *
     * <p>If we're inside a scalar subquery started by "(SELECT ..." then indent relative to the paren column,
     * not just depth*2. This keeps nested SELECT blocks readable and stable.</p>
     */
    private static int indentFor(int depth, Deque<ParenBlock> parenSubqueryStack) {
        if (parenSubqueryStack == null || parenSubqueryStack.isEmpty()) {
            return Math.max(0, depth) * 2;
        }
        ParenBlock pb = parenSubqueryStack.peek();
        if (depth < pb.depth) {
            return Math.max(0, depth) * 2;
        }
        // +2 so the content is indented within the parens.
        return pb.baseColumn + 2 + (depth - pb.depth) * 2;
    }

    private static int computeCdataInnerShift(Ctx ctx,
                                              int depth,
                                              int ctxDepth,
                                              int baseIndentAtCtx,
                                              int listCommaIndentAtCtx,
                                              boolean inParenList,
                                              int parenListDepth,
                                              int parenListIndent,
                                              Deque<ParenBlock> parenSubqueryStack) {

        // INSERT columns / VALUES list: use the paren list indent.
        if (inParenList && depth == parenListDepth) {
            return parenListIndent;
        }

        // List-style contexts use leading comma indentation.
        if ((ctx == Ctx.SELECT_LIST || ctx == Ctx.SET_LIST || ctx == Ctx.GROUP_LIST || ctx == Ctx.ORDER_LIST)
                && depth == ctxDepth) {
            return listCommaIndentAtCtx;
        }

        // WHERE/HAVING/MERGE ON usually break AND/OR at baseIndentAtCtx + 2.
        if ((ctx == Ctx.WHERE || ctx == Ctx.HAVING || ctx == Ctx.MERGE_ON) && depth == ctxDepth) {
            return baseIndentAtCtx + 2;
        }

        // JOIN ON breaks AND/OR at baseIndentAtCtx + 4.
        if (ctx == Ctx.JOIN_ON && depth == ctxDepth) {
            return baseIndentAtCtx + 4;
        }

        // Default: use current paren-subquery-aware indent.
        return indentFor(depth, parenSubqueryStack);
    }

    private static String indentNonEmptyLines(String s, int spaces) {
        if (s == null || s.isEmpty() || spaces <= 0) return s;

        String[] lines = s.split("\n", -1);
        String pad = " ".repeat(spaces);

        StringBuilder b = new StringBuilder(s.length() + (lines.length * spaces));
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (!ln.isEmpty()) b.append(pad);
            b.append(ln);
            if (i < lines.length - 1) b.append('\n');
        }
        return b.toString();
    }

    private static void appendPadSpaces(StringBuilder out, String keyword) {
        int spaces = Math.max(1, CLAUSE_PAD_COL - keyword.length());
        for (int i = 0; i < spaces; i++) out.append(' ');
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

    private static int currentColumn(StringBuilder out) {
        int lastNl = out.lastIndexOf("\n");
        return lastNl < 0 ? out.length() : (out.length() - lastNl - 1);
    }

    private static void rtrimLastLine(StringBuilder out) {
        int i = out.length() - 1;
        while (i >= 0) {
            char c = out.charAt(i);
            if (c == ' ' || c == '\t') {
                i--;
                continue;
            }
            break;
        }
        out.setLength(i + 1);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static String tryMergeClause(String firstUpper, SqlScan st) {
        int save = st.pos;
        String spaces = st.readSpaces();
        if (spaces.isEmpty()) {
            st.pos = save;
            return null;
        }
        if (!st.hasNext() || !isWordStart(st.peek())) {
            st.pos = save;
            return null;
        }
        String second = st.readWord();
        String secondUpper = second.toUpperCase(Locale.ROOT);

        // 2-word clauses
        if (firstUpper.equals("INSERT") && secondUpper.equals("INTO")) {
            return "INSERT INTO";
        }
        if (firstUpper.equals("DELETE") && secondUpper.equals("FROM")) {
            return "DELETE FROM";
        }
        if (firstUpper.equals("ORDER") && secondUpper.equals("BY")) {
            return "ORDER BY";
        }
        if (firstUpper.equals("GROUP") && secondUpper.equals("BY")) {
            return "GROUP BY";
        }
        if (firstUpper.equals("LEFT") && secondUpper.equals("JOIN")) {
            return "LEFT JOIN";
        }
        if (firstUpper.equals("RIGHT") && secondUpper.equals("JOIN")) {
            return "RIGHT JOIN";
        }
        if (firstUpper.equals("INNER") && secondUpper.equals("JOIN")) {
            return "INNER JOIN";
        }
        if (firstUpper.equals("FULL") && secondUpper.equals("JOIN")) {
            return "FULL JOIN";
        }
        if (firstUpper.equals("CROSS") && secondUpper.equals("JOIN")) {
            return "CROSS JOIN";
        }
        if (firstUpper.equals("WHEN") && secondUpper.equals("MATCHED")) {
            return "WHEN MATCHED";
        }
        if (firstUpper.equals("WHEN") && secondUpper.equals("NOT")) {
            int save2 = st.pos;
            String sp2 = st.readSpaces();
            if (!sp2.isEmpty() && st.peekWord("MATCHED")) {
                st.readWord();
                return "WHEN NOT MATCHED";
            }
            st.pos = save2;
            return "WHEN NOT";
        }

        st.pos = save;
        return null;
    }

    private static boolean lastKeywordLooksLikeInsertInto(StringBuilder out) {
        if (out == null) return false;
        String s = out.toString();
        int idx = s.lastIndexOf("INSERT");
        if (idx < 0) return false;
        int end = Math.min(s.length(), idx + 20);
        return s.substring(idx, end)
                .toUpperCase(Locale.ROOT)
                .contains("INSERT INTO");
    }

    private static Set<String> keywordSet() {
        Set<String> k = new HashSet<>();
        String[] arr = {
                "SELECT", "FROM", "WHERE", "AND", "OR", "IN", "EXISTS",
                "UPDATE", "SET", "DELETE", "INSERT", "INTO", "VALUES",
                "JOIN", "LEFT", "RIGHT", "INNER", "FULL", "CROSS", "ON",
                "GROUP", "BY", "ORDER", "HAVING",
                "MERGE", "USING", "WHEN", "MATCHED", "NOT", "THEN",
                "CASE", "ELSE", "END"
        };
        Collections.addAll(k, arr);
        return k;
    }

    private static final class ParenBlock {
        final int depth;      // depth after '(' consumed
        final int baseColumn; // column index of '('

        // Snapshot of outer formatting state (so nested SELECT in a scalar subquery
        // does not overwrite the parent's list context).
        final Ctx savedCtx;
        final int savedCtxDepth;
        final int savedBaseIndentAtCtx;
        final int savedListCommaIndentAtCtx;

        ParenBlock(int depth,
                   int baseColumn,
                   Ctx savedCtx,
                   int savedCtxDepth,
                   int savedBaseIndentAtCtx,
                   int savedListCommaIndentAtCtx) {
            this.depth = depth;
            this.baseColumn = baseColumn;
            this.savedCtx = savedCtx;
            this.savedCtxDepth = savedCtxDepth;
            this.savedBaseIndentAtCtx = savedBaseIndentAtCtx;
            this.savedListCommaIndentAtCtx = savedListCommaIndentAtCtx;
        }
    }

    private static final class CaseBlock {
        final int depth;
        final int baseColumn;

        CaseBlock(int depth, int baseColumn) {
            this.depth = depth;
            this.baseColumn = baseColumn;
        }
    }

    private static boolean isKeyword(String upper) {
        return upper != null && KEYWORDS.contains(upper);
    }

    private enum Ctx {
        NONE,
        SELECT_LIST,
        FROM,
        WHERE,
        HAVING,
        GROUP_LIST,
        ORDER_LIST,
        JOIN_ON,
        SET_LIST,
        MERGE_ON
    }
}
