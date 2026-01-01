package domain.convert;

import domain.mapping.ColumnMapping;
import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ConversionWarningSink;
import domain.model.WarningCode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SELECT list transformation:
 * - trailing comma -> leading comma
 * - alias rules
 * - comment placement (fixed column)
 * - deleted column comment emission
 */
final class MybatisSelectRenderer {

    private final ColumnMappingRegistry registry;
    private final CommentColumnAligner commentAligner;
    private final MybatisParamRenamer paramRenamer;
    private final CommentExtractor commentExtractor;
    private final boolean emitDeletedColumnComments;

    MybatisSelectRenderer(ColumnMappingRegistry registry,
                          CommentColumnAligner commentAligner,
                          MybatisParamRenamer paramRenamer,
                          CommentExtractor commentExtractor,
                          boolean emitDeletedColumnComments) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commentAligner = Objects.requireNonNull(commentAligner, "commentAligner");
        this.paramRenamer = Objects.requireNonNull(paramRenamer, "paramRenamer");
        this.commentExtractor = Objects.requireNonNull(commentExtractor, "commentExtractor");
        this.emitDeletedColumnComments = emitDeletedColumnComments;
    }

    private static boolean isTobeLikeMode(AliasSqlGenerator.Mode mode) {
        if (mode == null) return false;
        return mode == AliasSqlGenerator.Mode.TOBE
                || mode == AliasSqlGenerator.Mode.ASIS;
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        String x = (a == null) ? "" : a.trim();
        String y = (b == null) ? "" : b.trim();
        return x.equalsIgnoreCase(y);
    }

    private static String firstNonBlank(List<String> comments) {
        if (comments == null) return null;
        for (String c : comments) {
            if (c == null) continue;
            String t = c.trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private static List<String> mergeComments(String primary, List<String> others) {
        List<String> out = new ArrayList<>();

        if (primary != null && !primary.trim()
                .isEmpty()) {
            out.add(primary.trim());
        }

        if (others != null) {
            for (String c : others) {
                if (c == null) continue;
                String t = c.trim();
                if (t.isEmpty()) continue;
                boolean dup = false;
                for (String e : out) {
                    if (e.equalsIgnoreCase(t)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) out.add(t);
            }
        }

        return out;
    }

    private static String wrapBlockComment(String body) {
        if (body == null) return "/* */";
        String b = body.trim();
        if (b.isEmpty()) return "/* */";

        // keep Oracle hint style if present: /*+ ... */
        if (b.startsWith("+")) {
            return "/*" + b + " */";
        }
        return "/* " + b + " */";
    }

    String transformSelectBody(String selectBody,
                               Map<String, String> aliasTableMap,
                               AliasSqlGenerator.Mode mode,
                               ConversionContext ctx,
                               ConversionWarningSink sink) {

        ConversionWarningSink warningSink = (sink == null) ? ConversionWarningSink.none() : sink;

        List<SelectItem> items = splitSelectItems(selectBody);
        if (items.isEmpty()) {
            return selectBody;
        }

        StringBuilder out = new StringBuilder(selectBody.length() + 256);

        // ✅ 실제로 출력한 SELECT 아이템 개수(삭제된 컬럼은 제외)
        int emitted = 0;
        boolean anyOutput = false; // (삭제 주석 포함) 한 번이라도 출력했는지

        for (int i = 0; i < items.size(); i++) {
            SelectItem it = items.get(i);

            String commentStripped = stripTrailingBlockComment(it.rawExpr).trim();
            List<String> priorComments = it.trailingComments;
            String priorComment = firstNonBlank(priorComments); // may be null

            ParsedAlias pa = parseAlias(commentStripped);
            String exprOnly = pa.exprOnly.trim();
            String alias = pa.alias; // may be null

            ColumnRef ref = parseDirectColumnRef(exprOnly);

            ColumnMapping mapping = null;
            ResolveResult mappingResult = null;
            if (ref != null) {
                mappingResult = resolveMappingDetailed(aliasTableMap, ref.tableAlias, ref.column);
                mapping = mappingResult.mapping;
            } else {
                if (isMappableColumnIdAlias(alias)) {
                    mappingResult = resolveMappingDetailed(aliasTableMap, null, alias);
                    mapping = mappingResult.mapping;
                    if (mapping == null) {
                        mapping = registry.findByColumnOnly(alias);
                        if (mapping != null) {
                            mappingResult = new ResolveResult(mapping, ResolveStatus.FOUND, alias.trim()
                                    .toUpperCase(Locale.ROOT));
                        }
                    }
                } else {
                    mapping = inferMappingFromExpression(aliasTableMap, exprOnly);
                    // Only emit warnings when we can attribute the expression to a first column-ref.
                    ColumnRef first = (mapping == null) ? findFirstColumnRefInExpression(exprOnly) : null;
                    if (first != null) {
                        mappingResult = resolveMappingDetailed(aliasTableMap, first.tableAlias, first.column);
                    }
                }
            }

            // Ensure deleted mappings in SELECT phase also get a warning row even when mapping was inferred.
            if (isTobeLikeMode(mode) && mappingResult == null && mapping != null && registry.isDeleted(mapping)) {
                String tok = "";
                if (ref != null) {
                    tok = (ref.tableAlias == null ? "" : (ref.tableAlias + ".")) + ref.column;
                } else if (alias != null && !alias.isBlank()) {
                    tok = alias;
                } else {
                    ColumnRef first = findFirstColumnRefInExpression(exprOnly);
                    if (first != null) tok = (first.tableAlias == null ? "" : (first.tableAlias + ".")) + first.column;
                }
                mappingResult = new ResolveResult(mapping, ResolveStatus.FOUND, tok == null ? "" : tok);
            }

            // Task3: warnings for SELECT phase.
            // Keep it conservative: only emit when mode is TOBE-like and we have an attributable token.
            if (isTobeLikeMode(mode) && mappingResult != null) {
                emitSelectMappingWarnings(mappingResult, mode, ctx, warningSink);
            }

            // ============================================================
            // ✅ Deleted column handling:
            // - TOBE 컬럼ID가 비어있는 매핑(삭제)인 경우,
            //   "주석을 기존 자리(원래 항목 위치)"에 남기고 실제 항목은 제거한다.
            //
            // ✅ 핵심: 주석은 "뒤(트레일링)"에 붙이지 않는다.
            // ============================================================
            boolean deleted = (mapping != null && registry.isDeleted(mapping));
            if (deleted && emitDeletedColumnComments
                    && (mode == AliasSqlGenerator.Mode.TOBE || mode == AliasSqlGenerator.Mode.ASIS)) {

                String delKey = null;
                if (ref != null && ref.column != null && !ref.column.isBlank()) {
                    delKey = ref.column.trim()
                            .toUpperCase(Locale.ROOT);
                } else if (alias != null && !alias.isBlank()) {
                    delKey = alias.trim()
                            .toUpperCase(Locale.ROOT);
                } else {
                    ColumnRef inner = findFirstColumnRefInExpression(exprOnly);
                    if (inner != null && inner.column != null && !inner.column.isBlank()) {
                        delKey = inner.column.trim()
                                .toUpperCase(Locale.ROOT);
                    }
                }

                List<String> delComments = mergeComments(registry.deletedComment(delKey), priorComments);
                if (delComments.isEmpty()) delComments = Collections.singletonList("삭제된 컬럼");

                // ✅ 자리 고정:
                // - 첫 출력이면 SELECT 바로 뒤 1칸
                // - 그 외에는 새 줄 + 4칸 들여쓰기 (콤마는 다음 "실제 컬럼"이 가진다 => SQL 유효성 유지)
                String prefix = (!anyOutput) ? " " : "\n    ";
                out.append(prefix)
                        .append(wrapBlockComment(delComments.get(0)));
                for (int k = 1; k < delComments.size(); k++) {
                    out.append("\n    ")
                            .append(wrapBlockComment(delComments.get(k)));
                }

                anyOutput = true;
                continue;
            }

            String exprConverted =
                    (mode == AliasSqlGenerator.Mode.TOBE)
                            ? convertSqlFragmentToTobe(exprOnly, aliasTableMap, Collections.emptyMap())
                            : exprOnly;

            String outAlias = null;
            String outComment = null;
            boolean forceAsForAlias = true;

            if (mode == AliasSqlGenerator.Mode.ASIS) {
                if (mapping != null && !registry.isDeleted(mapping)) {
                    outAlias = mapping.tobeColumnId;
                    outComment = safe(mapping.tobeColumnName);
                } else {
                    outAlias = alias;
                    outComment = priorComment;
                }
                forceAsForAlias = true;

            } else {
                if (ref != null) {
                    boolean hadAliasInOriginal = (alias != null && !alias.isBlank());
                    boolean forceAliasToTobe = hadAliasInOriginal &&
                            (alias.equalsIgnoreCase(ref.column) || isMappableColumnIdAlias(alias));

                    if (mapping != null) {
                        if (!registry.isDeleted(mapping)) {
                            outComment = safe(mapping.tobeColumnName);
                            outAlias = forceAliasToTobe ? mapping.tobeColumnId : null;
                        } else {
                            // (삭제된 컬럼은 위에서 continue 처리됨)
                            outComment = priorComment;
                            outAlias = null;
                        }
                    } else {
                        outComment = priorComment;
                        outAlias = forceAliasToTobe ? alias : null;
                    }

                    forceAsForAlias = (outAlias != null);

                } else {
                    boolean hadAliasInOriginal = (alias != null && !alias.isBlank());

                    ColumnRef innerRef = null;
                    ColumnMapping innerMapping = null;

                    if (hadAliasInOriginal) {
                        innerRef = findFirstColumnRefInExpression(exprOnly);
                        if (innerRef != null) {
                            innerMapping = resolveMapping(aliasTableMap, innerRef.tableAlias, innerRef.column);
                        }
                    }

                    String innerAlias = null;
                    String innerComment = null;

                    if (innerRef != null) {
                        if (innerMapping != null) {
                            innerAlias = innerMapping.tobeColumnId;
                            innerComment = safe(innerMapping.tobeColumnName);
                        } else {
                            innerAlias = innerRef.column;
                            innerComment = priorComment;
                        }
                    }

                    boolean needInnerAliasOverride =
                            hadAliasInOriginal
                                    && innerAlias != null
                                    && (
                                    isBadExpressionAlias(alias, exprOnly)
                                            || !isMappableColumnIdAlias(alias)
                                            || !alias.equalsIgnoreCase(innerAlias)
                            );

                    if (needInnerAliasOverride) {
                        outAlias = innerAlias;
                        outComment = (innerComment != null && !innerComment.isBlank()) ? innerComment : priorComment;
                    } else {
                        if (mapping != null) {
                            if (!registry.isDeleted(mapping)) {
                                outAlias = mapping.tobeColumnId;
                                outComment = safe(mapping.tobeColumnName);
                            } else {
                                // (삭제된 컬럼은 위에서 continue 처리됨)
                                outAlias = alias;
                                outComment = priorComment;
                            }
                        } else {
                            outAlias = alias;
                            outComment = priorComment;
                        }
                    }

                    forceAsForAlias = (outAlias != null);
                }
            }

            String base = buildSelectBase(exprConverted, outAlias, forceAsForAlias);

            List<String> comments = mergeComments(outComment, priorComments);

            // ✅ leading comma 스타일(삭제된 컬럼 제외한 emitted 기준):
            //  - 첫 "실제 컬럼" 항목: SELECT 뒤 1칸
            //    (단, 삭제 주석이 먼저 나왔으면 새 줄 + 4칸)
            //  - 둘째부터: "\n    , " (expr 시작점 6)
            String prefix;
            int leadingWidth;

            if (emitted == 0) {
                if (!anyOutput) {
                    prefix = " ";
                    leadingWidth = 1;
                } else {
                    prefix = "\n    ";
                    leadingWidth = 4;
                }
            } else {
                prefix = "\n    , ";
                leadingWidth = 6;
            }

            String rendered = commentAligner.renderSelectWithFixedCommentColumn(base, comments, leadingWidth);
            out.append(prefix)
                    .append(rendered);

            emitted++;
            anyOutput = true;
        }

        out.append("\n");
        return out.toString();
    }

    private void emitSelectMappingWarnings(ResolveResult rr,
                                           AliasSqlGenerator.Mode mode,
                                           ConversionContext ctx,
                                           ConversionWarningSink sink) {
        if (rr == null || sink == null) return;
        String svc = ctx == null ? "" : ctx.getServiceClass();
        String ns = ctx == null ? "" : ctx.getNamespace();
        String id = ctx == null ? "" : ctx.getSqlId();

        if (rr.status == ResolveStatus.AMBIGUOUS) {
            sink.warn(new ConversionWarning(
                    WarningCode.AMBIGUOUS_COLUMN,
                    svc,
                    ns,
                    id,
                    "select column ambiguous",
                    rr.detail
            ));
            return;
        }

        if (rr.status == ResolveStatus.NOT_FOUND) {
            // Only meaningful for TOBE conversions.
            if (isTobeLikeMode(mode)) {
                sink.warn(new ConversionWarning(
                        WarningCode.COLUMN_MAPPING_NOT_FOUND,
                        svc,
                        ns,
                        id,
                        "select column mapping not found",
                        rr.detail
                ));
            }
            return;
        }

        if (rr.mapping != null && registry.isDeleted(rr.mapping)) {
            // We emit deleted column comments in SELECT, but also provide a warning row for auditing.
            sink.warn(new ConversionWarning(
                    WarningCode.DELETED_COLUMN_SKIPPED,
                    svc,
                    ns,
                    id,
                    "deleted column skipped in select",
                    rr.detail
            ));
        }
    }

    private String buildSelectBase(String expr, String alias, boolean forceAs) {
        String e = (expr == null) ? "" : expr;
        StringBuilder b = new StringBuilder(e.length() + 32);
        b.append(e);

        if (alias != null && !alias.isEmpty()) {
            b.append(forceAs ? " AS " : " ");
            b.append(alias);
        }
        return b.toString();
    }

    String convertSqlFragmentToTobe(String fragment,
                                    Map<String, String> aliasTableMap,
                                    Map<String, String> paramRenameMap) {
        if (fragment == null || fragment.isEmpty()) return fragment;

        StringBuilder out = new StringBuilder(fragment.length() + 32);

        MybatisSqlScan st = new MybatisSqlScan(fragment);
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
            if (st.peekIsCdata()) {
                out.append(st.readCdata());
                continue;
            }
            if (st.peekIsXmlTag()) {
                out.append(st.readXmlTag());
                continue;
            }

            if (st.peek() == '#') {
                String param = st.readHashParam(); // includes #...#
                out.append(paramRenamer.renameParamToken(param, paramRenameMap));
                continue;
            }

            if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                String ident = st.readIdentifier(); // may include dot parts
                String replaced = replaceIdentifierToTobe(ident, aliasTableMap);
                out.append(replaced);
                continue;
            }

            out.append(st.read());
        }

        return out.toString();
    }

    private String replaceIdentifierToTobe(String ident, Map<String, String> aliasTableMap) {
        if (ident == null) return null;

        String raw = ident;
        String u = raw.toUpperCase();

        String[] parts = u.split("\\.");
        if (parts.length == 1) {
            String col = parts[0];

            // ✅ 단독 컬럼은 "현재 SQL 스코프의 테이블" 기준으로 먼저 매핑 (중요!)
            ColumnMapping cm = resolveUnqualifiedMapping(aliasTableMap, col);
            if (cm == null) cm = registry.findByColumnOnly(col);

            if (cm != null) {
                String tobe = (cm.tobeColumnId == null) ? "" : cm.tobeColumnId.trim();
                // ✅ tobe 컬럼이 비어있으면(삭제/미정) 잘못된 치환을 피하고 원문 유지
                return tobe.isBlank() ? raw : cm.tobeColumnId;
            }
            return raw;
        }

        String left = parts[0];
        String right = parts[1];

        String table = (aliasTableMap == null) ? null : aliasTableMap.get(left);
        if (table != null) {
            ColumnMapping m = registry.find(table, right);
            if (m == null) m = registry.findByColumnOnly(right);

            if (m != null) {
                String tobe = (m.tobeColumnId == null) ? "" : m.tobeColumnId.trim();
                if (!tobe.isBlank()) return left + "." + m.tobeColumnId;

                // 삭제/미정이면 원문 유지(빈 alias.col 방지)
                return left + "." + right;
            }

            return left + "." + right;
        }

        ColumnMapping m = registry.findByColumnOnly(right);
        if (m != null) {
            String tobe = (m.tobeColumnId == null) ? "" : m.tobeColumnId.trim();
            if (!tobe.isBlank()) return left + "." + m.tobeColumnId;
        }

        return raw;
    }

// ------------------------------------------------------------
// DML item splitting (comma-separated lists) with the SAME comment
// attachment rules as SELECT list:
//  - standalone comment line right before a comma belongs to the NEXT item
//  - standalone comment line right after a comma belongs to the SAME item (as trailing comment)
//  - trailing inline comments (/* ... */ , -- ...) are collected and removed from the SQL token
// ------------------------------------------------------------

    private List<SelectItem> splitSelectItems(String selectBody) {
        List<String> rawItems = MybatisSqlTopLevelSplitter.splitTopLevelByComma(selectBody);
        if (rawItems.isEmpty()) return Collections.emptyList();

        // 1) 콤마 바로 위(이전 세그먼트 끝)에 있는 단독 주석 라인을 다음 세그먼트로 이동
        List<List<String>> carry = new ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) carry.add(new ArrayList<>());

        for (int i = 0; i < rawItems.size() - 1; i++) {
            CommentExtractor.Result tr = commentExtractor.extractTrailingStandaloneComments(rawItems.get(i));
            rawItems.set(i, tr.sql);
            if (!tr.comments.isEmpty()) {
                carry.get(i + 1)
                        .addAll(tr.comments);
            }
        }

        // 2) 콤마 뒤(세그먼트 시작)에 있는 단독 주석 라인은 같은 세그먼트의 뒤쪽 주석으로 이동
        for (int i = 0; i < rawItems.size(); i++) {
            CommentExtractor.Result lead = commentExtractor.extractLeadingStandaloneComments(rawItems.get(i));
            rawItems.set(i, lead.sql);
            if (!lead.comments.isEmpty()) {
                carry.get(i)
                        .addAll(lead.comments);
            }
        }

        // 3) 각 세그먼트의 끝에 붙은 inline 주석( /* ... */ , -- ... )도 trailingComments로 수집
        List<SelectItem> out = new ArrayList<>();
        for (int i = 0; i < rawItems.size(); i++) {
            String seg = rawItems.get(i);

            CommentExtractor.Result tail = commentExtractor.extractTrailingInlineComments(seg);
            String expr = CommentColumnAligner.rtrim((tail.sql == null) ? "" : tail.sql.trim());

            List<String> comments = new ArrayList<>();
            comments.addAll(carry.get(i));
            comments.addAll(tail.comments);

            out.add(new SelectItem(expr, comments));
        }

        return out;
    }

    private ParsedAlias parseAlias(String expr) {
        String t = expr.trim();
        if (t.isEmpty()) return new ParsedAlias(expr, null);

        if (t.endsWith(";")) t = t.substring(0, t.length() - 1)
                .trim();

        Matcher m = Pattern.compile("(?is)^(.*)\\bAS\\b\\s+([A-Z0-9_]{1,60})\\s*$")
                .matcher(t);
        if (m.find()) {
            return new ParsedAlias(m.group(1)
                    .trim(), m.group(2)
                    .trim());
        }

        Matcher m2 = Pattern.compile("(?is)^(.*?)(?:\\s+)([A-Z0-9_]{1,60})\\s*$")
                .matcher(t);
        if (m2.find()) {
            String before = m2.group(1)
                    .trim();
            String last = m2.group(2)
                    .trim();

            if (!before.isEmpty() && !endsWithOperator(before)) {
                return new ParsedAlias(before, last);
            }
        }

        return new ParsedAlias(t, null);
    }

    private boolean endsWithOperator(String s) {
        String t = s.trim();
        if (t.isEmpty()) return false;
        char c = t.charAt(t.length() - 1);
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == '=' || c == '<' || c == '>';
    }

    // ------------------------------------------------------------
    // Mapping resolution with status (Task3 warnings)
    // ------------------------------------------------------------

    private ColumnRef parseDirectColumnRef(String expr) {
        String t = expr.trim();
        Matcher m = Pattern.compile("^(?i)([A-Z0-9_]{1,30})\\s*$")
                .matcher(t);
        if (m.find()) {
            return new ColumnRef(null, m.group(1)
                    .toUpperCase());
        }
        Matcher m2 = Pattern.compile("^(?i)([A-Z0-9_]{1,30})\\s*\\.\\s*([A-Z0-9_]{1,30})\\s*$")
                .matcher(t);
        if (m2.find()) {
            return new ColumnRef(m2.group(1)
                    .toUpperCase(), m2.group(2)
                    .toUpperCase());
        }
        return null;
    }

    private ColumnMapping resolveMapping(Map<String, String> aliasTableMap, String tableAlias, String col) {
        return resolveMappingDetailed(aliasTableMap, tableAlias, col).mapping;
    }

    private ResolveResult resolveMappingDetailed(Map<String, String> aliasTableMap, String tableAlias, String col) {
        if (col == null || col.isBlank()) return new ResolveResult(null, ResolveStatus.NOT_FOUND, "");
        String c = col.trim()
                .toUpperCase(Locale.ROOT);

        // qualified: alias.col
        if (tableAlias != null && !tableAlias.isBlank()) {
            String table = (aliasTableMap == null) ? null : aliasTableMap.get(tableAlias.toUpperCase(Locale.ROOT));
            if (table != null) {
                ColumnMapping cm = registry.find(table, c);
                if (cm != null) return new ResolveResult(cm, ResolveStatus.FOUND, table + "." + c);

                cm = registry.findByTobeOnAsisTable(table, c);
                if (cm != null) return new ResolveResult(cm, ResolveStatus.FOUND, table + "." + c);
            }
        }

        // unqualified: COL -> FROM/JOIN 테이블 컨텍스트 기반 우선 탐색
        ResolveResult unq = resolveUnqualifiedMappingDetailed(aliasTableMap, c);
        if (unq.mapping != null) return unq;
        if (unq.status == ResolveStatus.AMBIGUOUS) return unq;

        // 마지막 fallback(유일할 때만): column-only
        ColumnMapping cm = registry.findByColumnOnly(c);
        if (cm != null) return new ResolveResult(cm, ResolveStatus.FOUND, c);

        cm = registry.findByTobeColumnOnly(c);
        if (cm != null) return new ResolveResult(cm, ResolveStatus.FOUND, c);

        return new ResolveResult(null, ResolveStatus.NOT_FOUND, unq.detail);
    }

    private List<String> collectUniqueAsisTables(Map<String, String> aliasTableMap) {
        if (aliasTableMap == null || aliasTableMap.isEmpty()) return Collections.emptyList();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String v : aliasTableMap.values()) {
            if (v == null) continue;
            String t = v.trim()
                    .toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) set.add(t);
        }
        return set.isEmpty() ? Collections.emptyList() : new ArrayList<>(set);
    }

// ------------------------------------------------------------
// Unqualified column mapping (FIX: avoid wrong column-only mapping)
// ------------------------------------------------------------

    /**
     * 별칭 없는 컬럼( COL )을 만났을 때:
     * - FROM/JOIN/UPDATE/DELETE/INSERT/MERGE 대상 테이블 후보가 1개면 그 테이블 기준으로 매핑
     * - 후보가 여러 개인 경우:
     * * 해당 컬럼이 존재하는 테이블이 1개로 유일하거나
     * * tobeColumnId가 유일하게 합의될 때만 매핑
     * - 그 외(모호)면 null (=> 변환하지 않음)
     */
    private ColumnMapping resolveUnqualifiedMapping(Map<String, String> aliasTableMap, String asisColUpper) {
        return resolveUnqualifiedMappingDetailed(aliasTableMap, asisColUpper).mapping;
    }

    private ResolveResult resolveUnqualifiedMappingDetailed(Map<String, String> aliasTableMap, String asisColUpper) {
        if (asisColUpper == null || asisColUpper.isBlank()) return new ResolveResult(null, ResolveStatus.NOT_FOUND, "");

        List<String> tables = collectUniqueAsisTables(aliasTableMap);
        if (tables.isEmpty()) return new ResolveResult(null, ResolveStatus.NOT_FOUND, "no_from_join_context");

        if (tables.size() == 1) {
            String t = tables.get(0);
            ColumnMapping cm = registry.find(t, asisColUpper);
            if (cm != null) return new ResolveResult(cm, ResolveStatus.FOUND, t + "." + asisColUpper);
            cm = registry.findByTobeOnAsisTable(t, asisColUpper);
            if (cm != null) return new ResolveResult(cm, ResolveStatus.FOUND, t + "." + asisColUpper);
            return new ResolveResult(null, ResolveStatus.NOT_FOUND, t + "." + asisColUpper);
        }

        ColumnMapping found = null;
        String foundTobe = null;
        List<String> hitTables = new ArrayList<>(4);
        LinkedHashSet<String> tobeSet = new LinkedHashSet<>();

        for (String t : tables) {
            if (t == null || t.isBlank()) continue;

            ColumnMapping cm = registry.find(t, asisColUpper);
            if (cm == null) cm = registry.findByTobeOnAsisTable(t, asisColUpper);
            if (cm == null) continue;

            String tobe = (cm.tobeColumnId == null) ? "" : cm.tobeColumnId.trim();

            hitTables.add(t);
            tobeSet.add(tobe.toUpperCase(Locale.ROOT));

            if (found == null) {
                found = cm;
                foundTobe = tobe;
            } else {
                if (!equalsIgnoreCaseSafe(foundTobe, tobe)) {
                    String detail = "asisCol=" + asisColUpper
                            + ";tables=" + String.join(",", hitTables)
                            + ";tobeCandidates=" + String.join(",", tobeSet);
                    return new ResolveResult(null, ResolveStatus.AMBIGUOUS, detail);
                }
            }
        }

        if (found == null) {
            return new ResolveResult(null, ResolveStatus.NOT_FOUND, "asisCol=" + asisColUpper + ";tables=" + String.join(",", tables));
        }

        return new ResolveResult(found, ResolveStatus.FOUND, found == null ? "" : (asisColUpper));
    }

    private ColumnMapping inferMappingFromExpression(Map<String, String> aliasTableMap, String exprOnly) {
        String u = exprOnly.toUpperCase();
        Matcher m = Pattern.compile("(?i)\\b([A-Z0-9_]{1,30})\\s*\\.\\s*([A-Z0-9_]{1,30})\\b")
                .matcher(u);
        if (m.find()) {
            ColumnMapping cm = resolveMapping(aliasTableMap, m.group(1), m.group(2));
            if (cm != null) return cm;
        }
        Matcher m2 = Pattern.compile("(?i)\\b([A-Z0-9_]{1,30})\\b")
                .matcher(u);
        while (m2.find()) {
            String token = m2.group(1);
            if (isSqlKeywordForExprScan(token)) continue;
            ColumnMapping cm = resolveMapping(aliasTableMap, null, token);
            if (cm != null) return cm;
        }
        return null;
    }

    private boolean isSqlKeyword(String t) {
        String u = t.toUpperCase();
        return u.equals("SELECT") || u.equals("FROM") || u.equals("WHERE") || u.equals("AND") || u.equals("OR")
                || u.equals("CASE") || u.equals("WHEN") || u.equals("THEN") || u.equals("ELSE") || u.equals("END")
                || u.equals("NVL") || u.equals("DECODE") || u.equals("SUM") || u.equals("MAX") || u.equals("MIN")
                || u.equals("COUNT") || u.equals("DISTINCT") || u.equals("AS") || u.equals("IN") || u.equals("IS")
                || u.equals("NULL") || u.equals("NOT") || u.equals("LIKE") || u.equals("ON") || u.equals("JOIN")
                || u.equals("LEFT") || u.equals("RIGHT") || u.equals("INNER") || u.equals("OUTER")
                || u.equals("GROUP") || u.equals("ORDER") || u.equals("BY") || u.equals("HAVING")
                || u.equals("INSERT") || u.equals("UPDATE") || u.equals("DELETE") || u.equals("MERGE")
                || u.equals("INTO") || u.equals("VALUES") || u.equals("SET");
    }

    private boolean isSqlKeywordForExprScan(String t) {
        if (t == null) return true;
        return isSqlKeyword(t);
    }

    private boolean isMappableColumnIdAlias(String alias) {
        if (alias == null || alias.isBlank()) return false;
        String a = alias.trim()
                .toUpperCase();
        return registry.findByColumnOnly(a) != null || registry.findByTobeColumnOnly(a) != null;
    }

    private boolean isBadExpressionAlias(String alias, String exprOnly) {
        if (alias == null || alias.isBlank() || exprOnly == null || exprOnly.isBlank()) return false;

        String a = alias.trim()
                .toUpperCase();

        Matcher fm = Pattern.compile("(?i)^\\s*([A-Z0-9_]{1,60})\\s*\\(")
                .matcher(exprOnly.trim());
        if (fm.find()) {
            String fn = fm.group(1)
                    .trim()
                    .toUpperCase();
            if (!fn.isEmpty() && a.equals(fn)) return true;
        }

        return isSqlKeywordForExprScan(a);
    }

    private ColumnRef findFirstColumnRefInExpression(String expr) {
        if (expr == null || expr.isBlank()) return null;

        MybatisSqlScan st = new MybatisSqlScan(expr);
        while (st.hasNext()) {
            if (st.peekIsLineComment()) {
                st.readLineComment();
                continue;
            }
            if (st.peekIsBlockComment()) {
                st.readBlockComment();
                continue;
            }
            if (st.peekIsSingleQuotedString()) {
                st.readSingleQuotedString();
                continue;
            }
            if (st.peekIsMyBatisParam()) {
                st.readMyBatisParam();
                continue;
            }

            if (SqlIdentifierUtil.isIdentStart(st.peek())) {
                String ident = st.readIdentifier();
                if (ident == null || ident.isBlank()) continue;

                String u = ident.toUpperCase(Locale.ROOT);

                if (isSqlKeywordForExprScan(u)) continue;

                st.readSpaces();
                if (st.peek() == '(') {
                    continue;
                }

                String[] parts = u.split("\\.");
                if (parts.length >= 2) {
                    String left = parts[0].trim();
                    String right = parts[1].trim();
                    if (!left.isEmpty() && !right.isEmpty() && !isSqlKeywordForExprScan(right)) {
                        return new ColumnRef(left, right);
                    }
                } else {
                    String one = u.trim();
                    if (!one.isEmpty() && !isSqlKeywordForExprScan(one)) {
                        return new ColumnRef(null, one);
                    }
                }
                continue;
            }

            st.read();
        }

        return null;
    }

    private String safe(String s) {
        return (s == null) ? "" : s.trim();
    }

    private String extractTrailingBlockComment(String t) {
        if (t == null) return null;
        String s = t.trim();
        if (!s.endsWith("*/")) return null;

        int p = s.lastIndexOf("/*");
        if (p < 0) return null;

        String c = s.substring(p + 2, s.length() - 2)
                .trim();
        return c.isEmpty() ? null : c;
    }

    private String stripTrailingBlockComment(String t) {
        if (t == null) return null;
        String s = t;
        int end = s.lastIndexOf("*/");
        int start = s.lastIndexOf("/*");
        if (start >= 0 && end > start && end == s.length() - 2) {
            return s.substring(0, start)
                    .trim();
        }
        return t;
    }

    private boolean hasBlockComment(String s) {
        return s != null && s.contains("/*") && s.contains("*/");
    }

    private enum ResolveStatus {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS
    }

    private static final class SelectItem {
        final String rawExpr;
        // comment bodies (without /* */)
        final List<String> trailingComments;

        SelectItem(String rawExpr, List<String> trailingComments) {
            this.rawExpr = rawExpr;
            if (trailingComments == null || trailingComments.isEmpty()) {
                this.trailingComments = Collections.emptyList();
            } else {
                List<String> tmp = new ArrayList<>();
                for (String c : trailingComments) {
                    if (c == null) continue;
                    String t = c.trim();
                    if (!t.isEmpty()) tmp.add(t);
                }
                this.trailingComments = tmp.isEmpty()
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(tmp);
            }
        }
    }
// ------------------------------------------------------------
// Block comment utils
// ------------------------------------------------------------

    private static final class ParsedAlias {
        final String exprOnly;
        final String alias;

        ParsedAlias(String exprOnly, String alias) {
            this.exprOnly = exprOnly;
            this.alias = alias;
        }
    }

    private static final class ColumnRef {
        final String tableAlias; // may be null
        final String column;

        ColumnRef(String tableAlias, String column) {
            this.tableAlias = tableAlias;
            this.column = column;
        }
    }

    private static final class ResolveResult {
        final ColumnMapping mapping;
        final ResolveStatus status;
        /**
         * extra context for warnings (e.g. candidates)
         */
        final String detail;

        ResolveResult(ColumnMapping mapping, ResolveStatus status, String detail) {
            this.mapping = mapping;
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }
    }


// ------------------------------------------------------------
// Standalone comment relocation within SELECT item lists
//
// Pattern we want to fix:
//   <item-A>
//       /* comment for item-B */
//   , <item-B>
//
// The comma-splitter attaches the comment to item-A (because it appears before the comma).
// We move such standalone comment-lines to item-B, and we also move leading standalone
// comments that appear right after the comma to the *end* of the same item.
// ------------------------------------------------------------
}