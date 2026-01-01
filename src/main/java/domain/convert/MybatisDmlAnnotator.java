package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;

import java.util.*;

/**
 * DML annotation + formatting:
 * - INSERT column list / VALUES: trailing -> leading comma + comments
 * - UPDATE SET: trailing -> leading comma + comments
 * - MERGE variants
 */
final class MybatisDmlAnnotator {

    private final ColumnMappingRegistry registry;
    private final CommentColumnAligner commentAligner;
    private final CommentExtractor commentExtractor;
    private final boolean emitDeletedColumnComments;

    MybatisDmlAnnotator(ColumnMappingRegistry registry,
                        CommentColumnAligner commentAligner,
                        CommentExtractor commentExtractor,
                        boolean emitDeletedColumnComments) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commentAligner = Objects.requireNonNull(commentAligner, "commentAligner");
        this.commentExtractor = Objects.requireNonNull(commentExtractor, "commentExtractor");
        this.emitDeletedColumnComments = emitDeletedColumnComments;
    }

    private static String stripLeadingDecorationsForLhs(String s) {
        if (s == null || s.isEmpty()) return "";

        int i = 0;
        while (i < s.length()) {
            // whitespace
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;

            // leading comma
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }

            // block comment
            if (i + 1 < s.length() && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j + 1 < s.length()) {
                    if (s.charAt(j) == '*' && s.charAt(j + 1) == '/') {
                        j += 2;
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }

            // line comment
            if (i + 1 < s.length() && s.charAt(i) == '-' && s.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < s.length() && s.charAt(j) != '\n') j++;
                i = j;
                continue;
            }

            break;
        }

        return s.substring(i)
                .trim();
    }

// ------------------------------------------------------------
// INSERT / MERGE INSERT: column list + VALUES list sync (deleted columns)
// ------------------------------------------------------------

    private static void consumeHintsAndSpaces(MybatisSqlScan st, StringBuilder out) {
        boolean progressed;
        do {
            progressed = false;

            if (st.peekIsLineComment()) {
                out.append(st.readLineComment());
                progressed = true;
            } else if (st.peekIsBlockComment()) {
                out.append(st.readBlockComment());
                progressed = true;
            }

            String ws = st.readSpaces();
            if (!ws.isEmpty()) {
                out.append(ws);
                progressed = true;
            }

        } while (progressed);
    }

    private static int lastLineLen(String s) {
        if (s == null) return 0;
        int n = s.length();
        int p1 = s.lastIndexOf('\n');
        int p2 = s.lastIndexOf('\r');
        int p = Math.max(p1, p2);
        return (p < 0) ? n : (n - (p + 1));
    }

    private static int lastLineIndentLen(String s) {
        if (s == null || s.isEmpty()) return 0;
        int p1 = s.lastIndexOf('\n');
        int p2 = s.lastIndexOf('\r');
        int p = Math.max(p1, p2);
        int start = (p < 0) ? 0 : (p + 1);

        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            break;
        }
        return i - start;
    }

    private static boolean startsWithNewlineWhitespace(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') return true;
            if (!Character.isWhitespace(c)) return false;
        }
        return false;
    }

    private static String stripLeadingComma(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i < s.length() && s.charAt(i) == ',') {
            int j = i + 1;
            while (j < s.length()) {
                char ch = s.charAt(j);
                if (ch == ' ' || ch == '\t') {
                    j++;
                    continue;
                }
                break;
            }
            return s.substring(0, i) + s.substring(j);
        }
        return s;
    }

    private static String prefixLeadingComma(String base, boolean newlineStyle) {
        String b = (base == null) ? "" : base;
        b = stripLeadingComma(b);

        if (newlineStyle) {
            int i = 0;
            while (i < b.length() && Character.isWhitespace(b.charAt(i))) i++;
            String ws = b.substring(0, i);
            String rest = ltrim(b.substring(i));
            return ws + ", " + rest;
        } else {
            return ", " + ltrim(b);
        }
    }

    private static String ltrim(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    /**
     * 선행 공백/개행을 그대로 보존하기 위한 유틸
     */
    private static String leadingWhitespace(String s) {
        if (s == null || s.isEmpty()) return "";
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static String trailingWhitespace(String s) {
        if (s == null || s.isEmpty()) return "";
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(e);
    }

    /**
     * 현재 StringBuilder가 "라인 시작(개행 이후의 들여쓰기 영역)"에 위치해 있는지 체크.
     * - splitTopLevelByComma로 분리하면, leading comma 포맷의 들여쓰기(예: "\n    ")가
     * 직전 세그먼트 끝에 남아있는 경우가 많다.
     */
    private static boolean isAtLineStart(StringBuilder sb) {
        if (sb == null || sb.length() == 0) return true;
        for (int i = sb.length() - 1; i >= 0; i--) {
            char ch = sb.charAt(i);
            if (ch == '\n' || ch == '\r') return true;
            if (ch == ' ' || ch == '\t') continue;
            return false;
        }
        return true;
    }

    /**
     * 삭제된 컬럼/값을 주석으로 남길 때, 주석이 "직전 항목 라인 끝"에 붙지 않도록 prefix를 계산한다.
     * - 이미 라인 시작이면(prefix 들여쓰기가 이미 들어있는 상태) 추가 prefix 없이 바로 주석을 붙인다.
     * - 라인 중간이면, 멀티라인(defaultIndent에 개행 포함)에서는 defaultIndent를 강제로 사용해 새 줄에 출력한다.
     */
    private static String prefixForDeletedInList(StringBuilder sb, String baseNoComma, String defaultIndent) {
        if (isAtLineStart(sb)) return "";
        String lead = leadingWhitespace(baseNoComma);
        if (lead == null || lead.isEmpty()) return defaultIndent;
        if (containsNewline(lead)) return lead;
        if (containsNewline(defaultIndent)) return defaultIndent;
        return lead;
    }

    private static String guessIndent(List<String> tokens) {
        if (tokens == null) return "";
        for (String t : tokens) {
            if (t == null) continue;
            int i = 0;
            while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++;
            if (i == 0) continue;

            String lead = t.substring(0, i);
            int nl1 = lead.lastIndexOf('\n');
            int nl2 = lead.lastIndexOf('\r');
            int nl = Math.max(nl1, nl2);
            return (nl >= 0) ? lead.substring(nl + 1) : lead;
        }
        return "";
    }

// ------------------------------------------------------------
// Helpers: select list splitting, parsing, mapping
// ------------------------------------------------------------

    private static boolean containsNewline(String s) {
        if (s == null || s.isEmpty()) return false;
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }

    private static String safe(String s) {
        return (s == null) ? "" : s;
    }

    private static List<String> mergeComments(String primary, List<String> prior) {
        List<String> out = new ArrayList<>();
        if (primary != null && !primary.isBlank()) out.add(primary.trim());
        if (prior != null && !prior.isEmpty()) {
            for (String p : prior) {
                if (p == null) continue;
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    private static String wrapBlockComment(String body) {
        String b = safe(body).trim();
        if (b.isEmpty()) b = " ";
        // keep existing block comment as-is
        if (b.startsWith("/*") && b.endsWith("*/")) return b;
        return "/* " + b + " */";
    }

    private static boolean hasBlockComment(String s) {
        if (s == null) return false;
        return s.contains("/*") && s.contains("*/");
    }

    /**
     * Parse LHS direct column ref:
     * - alias.col
     * - col
     * Best-effort: strips quotes and surrounding whitespace.
     */
    private static ColumnRef parseDirectColumnRef(String lhs) {
        if (lhs == null) return null;
        String t = lhs.trim();
        if (t.isEmpty()) return null;

        // remove wrapping parentheses
        while (t.startsWith("(") && t.endsWith(")") && t.length() > 2) {
            t = t.substring(1, t.length() - 1)
                    .trim();
        }

        // strip double quotes
        t = t.replace("\"", "");

        // remove trailing colon or other tokens
        // keep only last token-like part
        // split by whitespace and take last
        String[] partsWs = t.split("\\s+");
        if (partsWs.length > 1) t = partsWs[partsWs.length - 1];

        int dot = t.lastIndexOf('.');
        if (dot > 0 && dot < t.length() - 1) {
            String a = t.substring(0, dot)
                    .trim();
            String c = t.substring(dot + 1)
                    .trim();
            if (!a.isEmpty() && !c.isEmpty()) {
                return new ColumnRef(a, c);
            }
        }
        if (!t.isEmpty()) {
            return new ColumnRef(null, t);
        }
        return null;
    }

    String annotateDml(String sql, Map<String, String> aliasTableMap) {
        if (sql == null || sql.isEmpty()) return sql;

        // ✅ INSERT 컬럼리스트/VALUES를 "같은 기준"으로 처리(삭제된 컬럼 인덱스 동기화)
        // - 삭제된 컬럼은 컬럼/값 모두에서 제거하고, 위치에는 /* 삭제된 컬럼 */ 주석을 남긴다.
        String out = rewriteInsertColumnsAndValues(sql, aliasTableMap);
        out = annotateUpdateSetAssignments(out, aliasTableMap);         // ✅ UPDATE SET / MERGE UPDATE SET
        return out;
    }

// ------------------------------------------------------------
// leading comma helpers
// ------------------------------------------------------------

    /**
     * INSERT / MERGE INSERT 구문에서
     * 1) 컬럼 리스트는 주석 정렬 + leading comma
     * 2) VALUES 리스트는 leading comma
     * 3) "삭제된 컬럼"은 컬럼/VALUES 모두에서 함께 제거 + 주석 라인 남김
     */
    private String rewriteInsertColumnsAndValues(String sql, Map<String, String> aliasTableMap) {
        StringBuilder sb = new StringBuilder(sql.length() + 128);

        MybatisSqlScan st = new MybatisSqlScan(sql);
        String mergeIntoTable = null;

        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                sb.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                sb.append(st.readBlockComment());
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                sb.append(st.readSingleQuotedString());
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                sb.append(st.readDoubleQuotedString());
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                sb.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashToken()) {
                sb.append(st.readHashToken());
                continue;
            }
            if (st.peekIsCdata()) {
                sb.append(st.readCdata());
                continue;
            }
            if (st.peekIsXmlTag()) {
                sb.append(st.readXmlTag());
                continue;
            }

            // MERGE INTO <table> 캡쳐 (MERGE INSERT의 기본 테이블)
            if (st.peekWord("MERGE")) {
                sb.append(st.readWord());
                sb.append(st.readSpaces());

                if (st.peekWord("INTO")) {
                    sb.append(st.readWord());
                    sb.append(st.readSpaces());

                    consumeHintsAndSpaces(st, sb);

                    if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                        String tableToken = st.readIdentifier();
                        sb.append(tableToken);
                        mergeIntoTable = SqlIdentifierUtil.lastPart(tableToken)
                                .toUpperCase(Locale.ROOT);
                    }
                    continue;
                }
                continue;
            }

            // INSERT ... (cols) [VALUES (vals)]
            if (st.peekWord("INSERT")) {
                sb.append(st.readWord());
                sb.append(st.readSpaces());

                String tableName;

                if (st.peekWord("INTO")) {
                    sb.append(st.readWord());
                    sb.append(st.readSpaces());

                    String tableToken = st.readIdentifier();
                    sb.append(tableToken);
                    tableName = SqlIdentifierUtil.lastPart(tableToken)
                            .toUpperCase(Locale.ROOT);
                    sb.append(st.readSpaces());
                } else {
                    // MERGE INSERT: INSERT ( ... )
                    tableName = mergeIntoTable;
                    sb.append(st.readSpaces());
                }

                ColumnListRewrite colRw = null;
                if (st.peek() == '(') {
                    String colParen = st.readParenBlock();
                    colRw = rewriteInsertColumnListParen(colParen, tableName);
                    sb.append(colRw.rewrittenParen);
                }

                // colParen 뒤에 VALUES가 오는 경우에만 동기화 처리
                consumeHintsAndSpaces(st, sb);

                if (st.peekWord("VALUES")) {
                    sb.append(st.readWord());
                    sb.append(st.readSpaces());

                    if (st.peek() == '(') {
                        String valParen = st.readParenBlock();
                        if (colRw != null && !colRw.deletedIndexes.isEmpty() && emitDeletedColumnComments) {
                            sb.append(rewriteValuesParenWithDeletedIndexes(valParen, colRw));
                        } else {
                            // 삭제 동기화 필요 없으면 기존처럼 leading comma 변환만
                            sb.append(formatParenListLeadingComma(valParen));
                        }
                    }
                }

                continue;
            }

            // INSERT를 못 잡은 경우의 VALUES도 안전하게 처리(기존 로직 유지)
            if (st.peekWord("VALUES")) {
                sb.append(st.readWord());
                sb.append(st.readSpaces());

                if (st.peek() == '(') {
                    String paren = st.readParenBlock();
                    sb.append(formatParenListLeadingComma(paren));
                    continue;
                }
                continue;
            }

            sb.append(st.read());
        }

        return sb.toString();
    }

    /**
     * 컬럼 리스트 괄호 블록을 rewrite 하면서, 삭제된 컬럼의 "원본 인덱스"를 함께 기록한다.
     */
    private ColumnListRewrite rewriteInsertColumnListParen(String parenBlock, String tableName) {
        if (parenBlock == null || parenBlock.isEmpty() || parenBlock.length() < 2) {
            return new ColumnListRewrite(parenBlock, Collections.emptySet(), Collections.emptyMap());
        }

        String insideAll = parenBlock.substring(1, parenBlock.length() - 1);

        // If dynamic MyBatis XML tags exist inside the column list, do a conservative conversion:
        // - keep all tags/spacing/commas as-is
        // - convert only plain identifiers (column ids) outside protected zones
        String tn = (tableName == null) ? "" : tableName.trim()
                .toUpperCase(Locale.ROOT);

        if (containsMyBatisXmlTags(insideAll)) {
            String convertedInside = convertColumnIdsPreservingMyBatis(insideAll, tn);
            String rewritten = "(" + convertedInside + ")";
            return new ColumnListRewrite(rewritten, Collections.emptySet(), Collections.emptyMap());
        }
        String tailWs = trailingWhitespace(insideAll);
        String inside = insideAll.substring(0, insideAll.length() - tailWs.length());

        // ✅ SELECT와 동일한 "주석 귀속 규칙"으로 분리
        List<DmlItem> items = splitDmlItems(inside);
        List<String> tokensForIndent = new ArrayList<>();
        for (DmlItem it : items) tokensForIndent.add(it.rawSql);

        boolean multiline = containsNewline(insideAll);
        if (multiline && !containsNewline(tailWs)) {
            tailWs = "\n" + guessIndent(tokensForIndent);
        }
        String defaultIndent = multiline ? "\n" + guessIndent(tokensForIndent) : " ";
        String extraCommentIndent = multiline ? defaultIndent : "\n    ";

        StringBuilder sb = new StringBuilder(parenBlock.length() + 128);
        sb.append("(");

        int emitted = 0;
        Set<Integer> deletedIdx = new LinkedHashSet<>();
        Map<Integer, String> deletedLabel = new HashMap<>();

        for (int i = 0; i < items.size(); i++) {
            DmlItem it = items.get(i);

            String raw = it.rawSql;
            List<String> priorComments = it.trailingComments;

            String baseNoComma = CommentColumnAligner.rtrim(stripLeadingComma(raw));
            boolean newlineStyle = startsWithNewlineWhitespace(raw);

            String colToken = stripLeadingComma(raw).trim();
            String colNameOnly = SqlIdentifierUtil.lastPart(colToken)
                    .trim()
                    .toUpperCase(Locale.ROOT);

            ColumnMapping cm = null;
            if (!colNameOnly.isBlank()) {
                cm = registry.find(tn, colNameOnly);
                if (cm == null) cm = registry.findByTobeOnAsisTable(tn, colNameOnly);
                if (cm == null) cm = registry.findByColumnOnly(colNameOnly);
                if (cm == null) cm = registry.findByTobeColumnOnly(colNameOnly);
            }

            boolean deleted = (cm != null && registry.isDeleted(cm))
                    || (colNameOnly.isBlank() && baseNoComma.trim()
                    .isEmpty());

            if (deleted && emitDeletedColumnComments) {
                deletedIdx.add(i);
                String label = !colNameOnly.isBlank() ? colNameOnly
                        : (cm != null ? safe(cm.asisColumnId).toUpperCase(Locale.ROOT) : null);
                deletedLabel.put(i, label);

                List<String> delComments = mergeComments(registry.deletedComment(label), priorComments);
                if (delComments.isEmpty()) delComments = Collections.singletonList("삭제된 컬럼");

                String pfx = prefixForDeletedInList(sb, baseNoComma, defaultIndent);
                sb.append(pfx)
                        .append(wrapBlockComment(delComments.get(0)));
                for (int k = 1; k < delComments.size(); k++) {
                    sb.append(extraCommentIndent)
                            .append(wrapBlockComment(delComments.get(k)));
                }
                continue;
            }

            String base = baseNoComma;
            if (emitted > 0) {
                base = prefixLeadingComma(base, newlineStyle);
            }

            // ✅ SELECT 기준: "매핑 주석(TOBE 컬럼명)"을 1순위로 두고, 기존 주석은 2순위로 유지
            String primaryComment = (cm == null) ? null : pickDmlComment(cm);
            List<String> comments = mergeComments(primaryComment, priorComments);
            if (!comments.isEmpty()) {
                base = commentAligner.renderDmlWithFixedCommentColumn(base, comments);
            }

            sb.append(base);
            emitted++;
        }

        sb.append(tailWs);
        sb.append(")");

        return new ColumnListRewrite(sb.toString(), deletedIdx, deletedLabel);
    }

    /**
     * VALUES 괄호 블록에서 삭제된 인덱스를 제거(동기화)하고 leading comma 스타일로 재조립한다.
     */
    private String rewriteValuesParenWithDeletedIndexes(String parenBlock, ColumnListRewrite colRw) {
        if (parenBlock == null || parenBlock.isEmpty() || parenBlock.length() < 2) return parenBlock;

        String insideAll = parenBlock.substring(1, parenBlock.length() - 1);
        String tailWs = trailingWhitespace(insideAll);
        String inside = insideAll.substring(0, insideAll.length() - tailWs.length());

        // ✅ SELECT와 동일한 "주석 귀속 규칙"으로 분리
        List<DmlItem> items = splitDmlItems(inside);
        List<String> tokensForIndent = new ArrayList<>();
        for (DmlItem it : items) tokensForIndent.add(it.rawSql);

        boolean multiline = containsNewline(insideAll);
        if (multiline && !containsNewline(tailWs)) {
            tailWs = "\n" + guessIndent(tokensForIndent);
        }
        String defaultIndent = multiline ? "\n" + guessIndent(tokensForIndent) : " ";
        String extraCommentIndent = multiline ? defaultIndent : "\n    ";

        StringBuilder sb = new StringBuilder(parenBlock.length() + 128);
        sb.append("(");

        int emitted = 0;

        for (int i = 0; i < items.size(); i++) {
            DmlItem it = items.get(i);

            String raw = it.rawSql;
            List<String> priorComments = it.trailingComments;

            String baseNoComma = CommentColumnAligner.rtrim(stripLeadingComma(raw));
            boolean newlineStyle = startsWithNewlineWhitespace(raw);

            if (colRw != null && colRw.deletedIndexes.contains(i) && emitDeletedColumnComments) {
                String label = colRw.deletedLabel.get(i);

                List<String> delComments = mergeComments(registry.deletedComment(label), priorComments);
                if (delComments.isEmpty()) delComments = Collections.singletonList("삭제된 컬럼");

                String pfx = prefixForDeletedInList(sb, baseNoComma, defaultIndent);
                sb.append(pfx)
                        .append(wrapBlockComment(delComments.get(0)));
                for (int k = 1; k < delComments.size(); k++) {
                    sb.append(extraCommentIndent)
                            .append(wrapBlockComment(delComments.get(k)));
                }
                continue;
            }

            String base = baseNoComma;
            if (emitted > 0) {
                base = prefixLeadingComma(base, newlineStyle);
            }

            // VALUES도 기존 주석이 있으면 동일하게 COMMENT_COL에 맞춰 유지
            if (priorComments != null && !priorComments.isEmpty()) {
                base = commentAligner.renderDmlWithFixedCommentColumn(base, priorComments);
            }

            sb.append(base);
            emitted++;
        }

        sb.append(tailWs);
        sb.append(")");
        return sb.toString();
    }

    /**
     * INSERT/ MERGE INSERT 컬럼 리스트:
     * - INSERT INTO T ( ... )
     * - MERGE INTO T ... WHEN NOT MATCHED THEN INSERT ( ... )
     */
    private String annotateInsertColumnLists(String sql, Map<String, String> aliasTableMap) {
        StringBuilder sb = new StringBuilder(sql.length() + 64);

        MybatisSqlScan st = new MybatisSqlScan(sql);
        String mergeIntoTable = null;

        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                sb.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                sb.append(st.readBlockComment());
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                sb.append(st.readSingleQuotedString());
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                sb.append(st.readDoubleQuotedString());
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                sb.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashToken()) {
                sb.append(st.readHashToken());
                continue;
            }
            if (st.peekIsCdata()) {
                sb.append(st.readCdata());
                continue;
            }
            if (st.peekIsXmlTag()) {
                sb.append(st.readXmlTag());
                continue;
            }

            // MERGE INTO <table> 캡쳐
            if (st.peekWord("MERGE")) {
                sb.append(st.readWord()); // MERGE
                sb.append(st.readSpaces());

                if (st.peekWord("INTO")) {
                    sb.append(st.readWord()); // INTO
                    sb.append(st.readSpaces());

                    String tableToken = st.readIdentifier();
                    sb.append(tableToken);

                    mergeIntoTable = SqlIdentifierUtil.lastPart(tableToken)
                            .toUpperCase(Locale.ROOT);
                    continue;
                }
                continue;
            }

            if (st.peekWord("INSERT")) {
                sb.append(st.readWord()); // INSERT
                sb.append(st.readSpaces());

                String tableName;

                if (st.peekWord("INTO")) {
                    sb.append(st.readWord()); // INTO
                    sb.append(st.readSpaces());

                    String tableToken = st.readIdentifier();
                    sb.append(tableToken);

                    tableName = SqlIdentifierUtil.lastPart(tableToken)
                            .toUpperCase(Locale.ROOT);

                    sb.append(st.readSpaces());
                } else {
                    // MERGE INSERT: INSERT ( ... )
                    tableName = mergeIntoTable;
                    sb.append(st.readSpaces());
                }

                if (st.peek() == '(') {
                    String colList = st.readParenBlock();
                    String annotated = annotateColumnListParenLeadingComma(colList, tableName);
                    sb.append(annotated);
                    continue;
                }
                continue;
            }

            sb.append(st.read());
        }

        return sb.toString();
    }

    /**
     * VALUES (...) 리스트를 찾아 trailing -> leading comma로 변환
     * (DELETE는 VALUES가 없으니 자연스럽게 제외됨)
     */
    private String formatValuesParenBlocksLeadingComma(String sql) {
        StringBuilder out = new StringBuilder(sql.length() + 64);
        MybatisSqlScan st = new MybatisSqlScan(sql);

        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                out.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                out.append(st.readBlockComment());
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                out.append(st.readSingleQuotedString());
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                out.append(st.readDoubleQuotedString());
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                out.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashToken()) {
                out.append(st.readHashToken());
                continue;
            }
            if (st.peekIsCdata()) {
                out.append(st.readCdata());
                continue;
            }
            if (st.peekIsXmlTag()) {
                out.append(st.readXmlTag());
                continue;
            }

            if (st.peekWord("VALUES")) {
                out.append(st.readWord()); // VALUES
                out.append(st.readSpaces());

                if (st.peek() == '(') {
                    String paren = st.readParenBlock();
                    out.append(formatParenListLeadingComma(paren));
                    continue;
                }
                continue;
            }

            out.append(st.read());
        }

        return out.toString();
    }

    private String annotateUpdateSetAssignments(String sql, Map<String, String> aliasTableMap) {
        StringBuilder out = new StringBuilder(sql.length() + 64);
        MybatisSqlScan st = new MybatisSqlScan(sql);

        // ✅ UPDATE / MERGE UPDATE SET에서, 별칭 없는 컬럼도 정확히 매핑하기 위한 기본 테이블 컨텍스트
        // - 일반 UPDATE: UPDATE <table> ... SET
        // - MERGE UPDATE: MERGE INTO <table> ... WHEN MATCHED THEN UPDATE SET
        String defaultUpdateTable = null; // ASIS table id (UPPER)
        String mergeIntoTable = null;     // ASIS table id (UPPER)

        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                out.append(st.readLineComment());
                continue;
            }
            if (st.peekIsBlockComment()) {
                out.append(st.readBlockComment());
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                out.append(st.readSingleQuotedString());
                continue;
            }
            if (st.peekIsDoubleQuotedString()) {
                out.append(st.readDoubleQuotedString());
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                out.append(st.readMyBatisParam());
                continue;
            }
            if (st.peekIsHashToken()) {
                out.append(st.readHashToken());
                continue;
            }
            if (st.peekIsCdata()) {
                out.append(st.readCdata());
                continue;
            }
            if (st.peekIsXmlTag()) {
                out.append(st.readXmlTag());
                continue;
            }

            // MERGE INTO <table> 캡쳐 (MERGE UPDATE의 기본 테이블)
            if (st.peekWord("MERGE")) {
                out.append(st.readWord()); // MERGE
                out.append(st.readSpaces());

                if (st.peekWord("INTO")) {
                    out.append(st.readWord()); // INTO
                    out.append(st.readSpaces());

                    // 힌트/주석이 끼어도 테이블까지 전진
                    consumeHintsAndSpaces(st, out);

                    if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                        String tableToken = st.readIdentifier();
                        out.append(tableToken);
                        mergeIntoTable = SqlIdentifierUtil.lastPart(tableToken)
                                .toUpperCase(Locale.ROOT);
                    }
                    continue;
                }
                continue;
            }

            // UPDATE <table> 캡쳐 (일반 UPDATE의 기본 테이블)
            // - MERGE UPDATE는 "UPDATE SET" 형태이므로 테이블이 없다 => mergeIntoTable을 기본 테이블로 사용
            if (st.peekWord("UPDATE")) {
                out.append(st.readWord()); // UPDATE
                out.append(st.readSpaces());

                consumeHintsAndSpaces(st, out);

                if (st.peekWord("SET")) {
                    // MERGE ... WHEN MATCHED THEN UPDATE SET
                    if (mergeIntoTable != null && !mergeIntoTable.isBlank()) {
                        defaultUpdateTable = mergeIntoTable;
                    }
                    continue;
                }

                if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                    String tableToken = st.readIdentifier();
                    out.append(tableToken);
                    defaultUpdateTable = SqlIdentifierUtil.lastPart(tableToken)
                            .toUpperCase(Locale.ROOT);
                    continue;
                }

                continue;
            }

            if (st.peekWord("SET")) {
                out.append(st.readWord()); // SET
                out.append(st.readSpaces());

                String assignments = st.readUntilSetTerminator(); // ✅ MERGE WHEN 경계 포함
                out.append(annotateAssignmentsChunkLeadingComma(assignments, aliasTableMap, defaultUpdateTable));
                continue;
            }

            out.append(st.read());
        }

        return out.toString();
    }

    /**
     * UPDATE SET / MERGE UPDATE SET:
     * - splitTopLevelByComma로 나눈 뒤, 각 항목을 leading comma로 재조립
     * - 마지막 assignment 뒤에 WHERE/WHEN 등이 붙는 케이스(SYSDATEWHERE 등) 방지 위해 trailing whitespace 보존/보강
     * <p>
     * ✅ SELECT 기준과 일치:
     * - 삭제된 컬럼(TOBE 컬럼ID 비어있음)은 assignment를 제거하고, 해당 위치에 "삭제된 컬럼" 주석 라인을 남긴다.
     * - 주석은 "TOBE 컬럼명"을 1순위로 하고, 기존 주석은 2순위로 멀티 라인으로 유지한다.
     * - 단독 주석 라인의 귀속(콤마 위/아래) 규칙도 SELECT와 동일하게 처리한다.
     */
    private String annotateAssignmentsChunkLeadingComma(String chunk,
                                                        Map<String, String> aliasTableMap,
                                                        String defaultUpdateTable) {
        if (chunk == null || chunk.isEmpty()) return chunk;

        // ✅ 마지막 assignment 뒤 trailing whitespace(특히 newline)를 보존한다.
        String tailWs = trailingWhitespace(chunk);
        String body = chunk.substring(0, chunk.length() - tailWs.length());

        // WHERE/WHEN 경계는 다음 라인으로 내리는 것이 안전 (요구사항)
        if (tailWs.isEmpty() || !containsNewline(tailWs)) {
            tailWs = "\n";
        }

        // ✅ SELECT와 동일한 "주석 귀속 규칙"으로 분리
        List<DmlItem> items = splitDmlItems(body);
        List<String> tokensForIndent = new ArrayList<>();
        for (DmlItem it : items) tokensForIndent.add(it.rawSql);

        StringBuilder sb = new StringBuilder(chunk.length() + 64);

        String defaultTable = (defaultUpdateTable == null) ? null : defaultUpdateTable.trim()
                .toUpperCase(Locale.ROOT);

        // ✅ 실제로 출력한 assignment 개수(삭제된 컬럼은 제외)
        int emitted = 0;
        boolean multiline = containsNewline(body);
        String defaultIndent = multiline ? "\n" + guessIndent(tokensForIndent) : " ";
        String extraCommentIndent = multiline ? defaultIndent : "\n    ";

        for (int i = 0; i < items.size(); i++) {
            DmlItem it = items.get(i);

            String raw = it.rawSql;
            List<String> priorComments = it.trailingComments;

            // ✅ 기존 leading comma가 있었더라도 안전하게 제거 후 다시 적용
            String baseNoComma = CommentColumnAligner.rtrim(stripLeadingComma(raw));
            boolean newlineStyle = startsWithNewlineWhitespace(raw);

            int eq = MybatisSqlTopLevelSplitter.indexOfTopLevelEquals(baseNoComma);
            ColumnMapping cm = null;
            String lhsKeyUpper = null;

            if (eq > 0) {
                String lhsRaw = baseNoComma.substring(0, eq);
                String lhs = stripLeadingDecorationsForLhs(lhsRaw);

                ColumnRef ref = parseDirectColumnRef(lhs);
                if (ref != null) {
                    lhsKeyUpper = (ref.column == null) ? null : ref.column.trim()
                            .toUpperCase(Locale.ROOT);
                    cm = resolveMappingForUpdateAssignment(aliasTableMap, defaultTable, ref);
                } else {
                    // 방어: 주석/잡토큰이 남아 direct-ref로 못 잡는 경우
                    String key = SqlIdentifierUtil.lastPart(lhs)
                            .trim()
                            .toUpperCase(Locale.ROOT);
                    lhsKeyUpper = key;

                    if (defaultTable != null && !defaultTable.isBlank()) {
                        cm = registry.find(defaultTable, key);
                        if (cm == null) cm = registry.findByTobeOnAsisTable(defaultTable, key);
                    }

                    if (cm == null) {
                        cm = registry.findByColumnOnly(key);
                        if (cm == null) cm = registry.findByTobeColumnOnly(key);
                    }
                }
            }

            boolean deleted = (cm != null && registry.isDeleted(cm));
            if (deleted && emitDeletedColumnComments) {
                List<String> delComments = mergeComments(registry.deletedComment(lhsKeyUpper), priorComments);
                if (delComments.isEmpty()) delComments = Collections.singletonList("삭제된 컬럼");

                String pfx = prefixForDeletedInList(sb, baseNoComma, defaultIndent);
                sb.append(pfx)
                        .append(wrapBlockComment(delComments.get(0)));
                for (int k = 1; k < delComments.size(); k++) {
                    sb.append(extraCommentIndent)
                            .append(wrapBlockComment(delComments.get(k)));
                }
                continue;
            }

            String base = baseNoComma;
            if (emitted > 0) {
                base = prefixLeadingComma(base, newlineStyle);
            }

            String primaryComment = (cm == null) ? null : pickDmlComment(cm);
            List<String> comments = mergeComments(primaryComment, priorComments);
            if (!comments.isEmpty()) {
                base = commentAligner.renderDmlWithFixedCommentColumn(base, comments);
            }

            sb.append(base);
            emitted++;
        }

        sb.append(tailWs);
        return sb.toString();
    }

    private ColumnMapping resolveMappingForUpdateAssignment(Map<String, String> aliasTableMap,
                                                            String defaultTable,
                                                            ColumnRef ref) {
        if (ref == null) return null;

        // alias.col
        if (ref.tableAlias != null && !ref.tableAlias.isBlank()) {
            return resolveMapping(aliasTableMap, ref.tableAlias, ref.column);
        }

        String col = (ref.column == null) ? null : ref.column.trim()
                .toUpperCase(Locale.ROOT);
        if (col == null || col.isBlank()) return null;

        // unqualified col: prefer UPDATE target table
        if (defaultTable != null && !defaultTable.isBlank()) {
            ColumnMapping cm = resolveUnqualifiedMapping(aliasTableMap, col);
            if (cm == null) cm = registry.findByColumnOnly(col);
            if (cm != null) return cm;

            cm = registry.findByTobeOnAsisTable(defaultTable, col);
            if (cm != null) return cm;
        }

        return resolveMapping(aliasTableMap, null, col);
    }

// ------------------------------------------------------------
// Missing helpers (added for layered refactor completeness)
// ------------------------------------------------------------

    private String pickDmlComment(ColumnMapping cm) {
        if (cm == null) return "";
        String name = safe(cm.tobeColumnName);
        if (!name.isBlank()) return name;

        // 컬럼명이 비어있으면 최소한 TOBE 컬럼ID라도 붙인다
        return safe(cm.tobeColumnId);
    }

    private List<DmlItem> splitDmlItems(String body) {
        List<String> rawItems = MybatisSqlTopLevelSplitter.splitTopLevelByComma(body);
        if (rawItems.isEmpty()) return Collections.emptyList();

        // 1) 콤마 바로 위(이전 세그먼트 끝)에 있는 단독 주석 라인을 다음 세그먼트로 이동
        List<List<String>> carry = new ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) carry.add(new ArrayList<>());

        for (int i = 0; i < rawItems.size() - 1; i++) {
            CommentExtractor.Result tr = commentExtractor.extractTrailingStandaloneComments(rawItems.get(i));
            rawItems.set(i, tr.sql);
            if (!tr.comments.isEmpty()) carry.get(i + 1)
                    .addAll(tr.comments);
        }

        // 2) 콤마 뒤(세그먼트 시작)에 있는 단독 주석 라인은 같은 세그먼트의 뒤쪽 주석으로 이동
        for (int i = 0; i < rawItems.size(); i++) {
            CommentExtractor.Result lead = commentExtractor.extractLeadingStandaloneComments(rawItems.get(i));
            rawItems.set(i, lead.sql);
            if (!lead.comments.isEmpty()) carry.get(i)
                    .addAll(lead.comments);
        }

        // 3) 각 세그먼트의 끝에 붙은 inline 주석도 trailingComments로 수집
        List<DmlItem> out = new ArrayList<>();
        for (int i = 0; i < rawItems.size(); i++) {
            String seg = rawItems.get(i);

            CommentExtractor.Result tail = commentExtractor.extractTrailingInlineComments(seg);
            String sql = CommentColumnAligner.rtrim((tail.sql == null) ? "" : tail.sql);

            List<String> comments = new ArrayList<>();
            comments.addAll(carry.get(i));
            comments.addAll(tail.comments);

            out.add(new DmlItem(sql, comments));
        }

        return out;
    }

    private String annotateColumnListParenLeadingComma(String parenBlock, String tableName) {
        if (parenBlock == null || parenBlock.isEmpty()) return parenBlock;
        if (parenBlock.length() < 2) return parenBlock;

        String insideAll = parenBlock.substring(1, parenBlock.length() - 1);

        // 닫는 괄호를 새 줄로 내리기 위해 trailing whitespace 분리
        String tailWs = trailingWhitespace(insideAll);
        String inside = insideAll.substring(0, insideAll.length() - tailWs.length());

        List<String> cols = MybatisSqlTopLevelSplitter.splitTopLevelByComma(inside);
        String tn = (tableName == null) ? "" : tableName.trim()
                .toUpperCase(Locale.ROOT);

        boolean multiline = containsNewline(insideAll);
        if (multiline && !containsNewline(tailWs)) {
            tailWs = "\n" + guessIndent(cols);
        }

        StringBuilder sb = new StringBuilder(parenBlock.length() + 128);
        sb.append("(");

        // ✅ 실제로 출력한 컬럼 개수(삭제된 컬럼은 제외)
        int emitted = 0;
        String defaultIndent = multiline ? "\n" + guessIndent(cols) : " ";

        for (int i = 0; i < cols.size(); i++) {
            String raw = cols.get(i);

            String baseNoComma = CommentColumnAligner.rtrim(stripLeadingComma(raw));
            boolean newlineStyle = startsWithNewlineWhitespace(raw);

            String colToken = stripLeadingComma(raw).trim();
            String colNameOnly = SqlIdentifierUtil.lastPart(colToken)
                    .trim()
                    .toUpperCase(Locale.ROOT);

            ColumnMapping cm = null;
            if (!colNameOnly.isBlank()) {
                cm = registry.find(tn, colNameOnly);
                if (cm == null) cm = registry.findByTobeOnAsisTable(tn, colNameOnly);
                if (cm == null) cm = registry.findByColumnOnly(colNameOnly);
                if (cm == null) cm = registry.findByTobeColumnOnly(colNameOnly);
            }

            boolean deleted = cm != null && registry.isDeleted(cm);
            // 이미 변환 과정에서 컬럼 토큰이 비어버린 경우(",\n\n," 같은 케이스)도 삭제로 취급
            if (colNameOnly.isBlank() && baseNoComma.trim()
                    .isEmpty()) deleted = true;

            if (deleted && emitDeletedColumnComments) {
                sb.append(prefixForDeletedInList(sb, baseNoComma, defaultIndent))
                        .append(wrapBlockComment(registry.deletedComment(colNameOnly)));
                continue;
            }

            String base = baseNoComma;
            if (emitted > 0) {
                base = prefixLeadingComma(base, newlineStyle);
            }

            if (cm != null && !hasBlockComment(base)) {
                String c = safe(cm.tobeColumnName);
                if (!c.isBlank()) {
                    base = commentAligner.renderDmlWithFixedCommentColumn(base, c);
                }
            }

            sb.append(base);
            emitted++;
        }

        sb.append(tailWs);
        sb.append(")");
        return sb.toString();
    }

    /**
     * VALUES (...) 리스트를 trailing -> leading comma로 변환 (주석 추가 X)
     */
    private String formatParenListLeadingComma(String parenBlock) {
        if (parenBlock == null || parenBlock.isEmpty()) return parenBlock;
        if (parenBlock.length() < 2) return parenBlock;

        String insideAll = parenBlock.substring(1, parenBlock.length() - 1);
        String tailWs = trailingWhitespace(insideAll);
        String inside = insideAll.substring(0, insideAll.length() - tailWs.length());

        List<String> vals = MybatisSqlTopLevelSplitter.splitTopLevelByComma(inside);

        boolean multiline = containsNewline(insideAll);
        if (multiline && !containsNewline(tailWs)) {
            tailWs = "\n" + guessIndent(vals);
        }

        StringBuilder sb = new StringBuilder(parenBlock.length() + 128);
        sb.append("(");

        for (int i = 0; i < vals.size(); i++) {
            String raw = vals.get(i);

            String base = CommentColumnAligner.rtrim(stripLeadingComma(raw));
            boolean newlineStyle = startsWithNewlineWhitespace(raw);

            if (i > 0) {
                base = prefixLeadingComma(base, newlineStyle);
            }

            sb.append(base);
        }

        sb.append(tailWs);
        sb.append(")");
        return sb.toString();
    }

    private ColumnMapping resolveUnqualifiedMapping(Map<String, String> aliasTableMap, String colUpper) {
        if (colUpper == null || colUpper.isBlank()) return null;
        // if single table in scope, use it
        if (aliasTableMap != null) {
            Set<String> tables = new LinkedHashSet<>();
            for (String v : aliasTableMap.values()) {
                if (v == null) continue;
                String u = v.trim()
                        .toUpperCase(Locale.ROOT);
                if (!u.isEmpty()) tables.add(u);
            }
            if (tables.size() == 1) {
                String table = tables.iterator()
                        .next();
                ColumnMapping cm = registry.find(table, colUpper);
                if (cm == null) cm = registry.findByTobeOnAsisTable(table, colUpper);
                return cm;
            }
        }
        return null;
    }

    private ColumnMapping resolveMapping(Map<String, String> aliasTableMap, String tableAlias, String colUpper) {
        if (colUpper == null || colUpper.isBlank()) return null;

        if (tableAlias != null && aliasTableMap != null) {
            String keyUpper = tableAlias.trim()
                    .toUpperCase(Locale.ROOT);
            String table = aliasTableMap.get(keyUpper);
            if (table == null) {
                // try raw key
                table = aliasTableMap.get(tableAlias.trim());
            }
            if (table != null && !table.isBlank()) {
                String tu = table.trim()
                        .toUpperCase(Locale.ROOT);
                ColumnMapping cm = registry.find(tu, colUpper);
                if (cm == null) cm = registry.findByTobeOnAsisTable(tu, colUpper);
                if (cm != null) return cm;
            }
        }

        ColumnMapping cm = registry.findByColumnOnly(colUpper);
        if (cm == null) cm = registry.findByTobeColumnOnly(colUpper);
        return cm;
    }

    /**
     * True if the text contains MyBatis dynamic XML tags (e.g. &lt;if&gt;, &lt;trim&gt;, &lt;foreach&gt;).
     * This check ignores comments/strings/MyBatis parameters.
     */
    private boolean containsMyBatisXmlTags(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.indexOf('<') < 0) return false;

        MybatisSqlScan scan = new MybatisSqlScan(text);
        while (scan.hasNext()) {
            if (scan.peekIsLineComment()) {
                scan.readLineComment();
                continue;
            }
            if (scan.peekIsBlockComment()) {
                scan.readBlockComment();
                continue;
            }
            if (scan.peekIsSingleQuotedString()) {
                scan.readSingleQuotedString();
                continue;
            }
            if (scan.peekIsDoubleQuotedString()) {
                scan.readDoubleQuotedString();
                continue;
            }
            if (scan.peekIsMyBatisParam()) {
                scan.readMyBatisParam();
                continue;
            }
            if (scan.peekIsCdata()) {
                scan.readCdata();
                continue;
            }

            if (scan.peekIsXmlTag()) return true;

            scan.read();
        }
        return false;
    }

    /**
     * Convert column identifiers in a fragment while preserving MyBatis XML tags and other "protected" regions
     * (comments/strings/MyBatis params/CDATA).
     * <p>
     * This is intentionally conservative: it converts only plain identifiers (A-Z0-9_ etc.) outside protected zones.
     */
    private String convertColumnIdsPreservingMyBatis(String text, String tableUpperOrNull) {
        if (text == null || text.isEmpty()) return text;

        String tn = (tableUpperOrNull == null) ? null : tableUpperOrNull.trim()
                .toUpperCase(Locale.ROOT);
        if (tn != null && tn.isEmpty()) tn = null;

        StringBuilder out = new StringBuilder(text.length() + 32);
        MybatisSqlScan scan = new MybatisSqlScan(text);

        while (scan.hasNext()) {
            if (scan.peekIsLineComment()) {
                out.append(scan.readLineComment());
                continue;
            }
            if (scan.peekIsBlockComment()) {
                out.append(scan.readBlockComment());
                continue;
            }
            if (scan.peekIsSingleQuotedString()) {
                out.append(scan.readSingleQuotedString());
                continue;
            }
            if (scan.peekIsDoubleQuotedString()) {
                out.append(scan.readDoubleQuotedString());
                continue;
            }
            if (scan.peekIsMyBatisParam()) {
                out.append(scan.readMyBatisParam());
                continue;
            }
            if (scan.peekIsCdata()) {
                out.append(scan.readCdata());
                continue;
            }
            if (scan.peekIsXmlTag()) {
                out.append(scan.readXmlTag());
                continue;
            }

            if (scan.peekIsIdentifierStart()) {
                String ident = scan.readIdentifier();
                String mapped = mapColumnIdentifierConservative(ident, tn);
                out.append(mapped);
                continue;
            }

            out.append(scan.read());
        }
        return out.toString();
    }

    private String mapColumnIdentifierConservative(String ident, String tableUpperOrNull) {
        if (ident == null || ident.isEmpty()) return ident;

        String keyUpper = ident.toUpperCase(Locale.ROOT);
        ColumnMapping cm = null;

        if (tableUpperOrNull != null) {
            cm = registry.find(tableUpperOrNull, keyUpper);
        }
        if (cm == null) cm = registry.findByColumnOnly(keyUpper);
        if (cm == null) return ident;

        // deleted mapping (empty TOBE column)
        if (registry.isDeleted(cm)) {
            if (!emitDeletedColumnComments) return ident;
            String label = keyUpper;
            return ident + " " + wrapBlockComment(registry.deletedComment(label));
        }

        String tobe = cm.tobeColumnId == null ? "" : cm.tobeColumnId.trim();
        return tobe.isEmpty() ? ident : tobe;
    }

    private static final class ColumnListRewrite {
        final String rewrittenParen;                       // "( ... )"
        final Set<Integer> deletedIndexes;       // original index
        final Map<Integer, String> deletedLabel; // idx -> label(upper)

        ColumnListRewrite(String rewrittenParen,
                          Set<Integer> deletedIndexes,
                          Map<Integer, String> deletedLabel) {
            this.rewrittenParen = rewrittenParen;
            this.deletedIndexes = (deletedIndexes == null) ? Collections.emptySet() : deletedIndexes;
            this.deletedLabel = (deletedLabel == null) ? Collections.emptyMap() : deletedLabel;
        }
    }

    private static final class DmlItem {
        final String rawSql;             // item SQL without trailing inline comments
        final List<String> trailingComments; // comment bodies

        DmlItem(String rawSql, List<String> trailingComments) {
            this.rawSql = (rawSql == null) ? "" : rawSql;
            this.trailingComments = (trailingComments == null || trailingComments.isEmpty())
                    ? Collections.emptyList()
                    : trailingComments;
        }
    }

    private static final class ColumnRef {
        final String tableAlias; // may be null
        final String column;     // may be null

        ColumnRef(String tableAlias, String column) {
            this.tableAlias = tableAlias;
            this.column = column;
        }
    }

}