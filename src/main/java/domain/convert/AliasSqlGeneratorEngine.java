package domain.convert;

import domain.convert.AliasSqlGenerator.Mode;
import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ConversionWarningSink;
import domain.model.WarningCode;

import java.util.Collections;
import java.util.Map;

/**
 * Internal implementation of {@link AliasSqlGenerator}.
 *
 * <p>Split points (SRP):
 * <ul>
 *   <li>{@link SqlSegmentTransformer}: finds each SELECT statement and rewrites only SELECT-body</li>
 *   <li>{@link BareColumnClauseConverter}: TOBE mode fallback for bare columns in WHERE/ON/GROUP/ORDER/SET</li>
 *   <li>{@link TableIdConverter}: TOBE mode table-id replacement (ASIS table -> TOBE table)</li>
 *   <li>{@link SqlPostProcessor}: keyword glue fix + optional prettifier</li>
 * </ul>
 */
final class AliasSqlGeneratorEngine {

    private final ColumnMappingRegistry registry;
    private final SelectLineTransformer transformer;

    private final SqlSegmentTransformer segmentTransformer;
    private final BareColumnClauseConverter bareColumnClauseConverter;
    private final TableIdConverter tableIdConverter;
    private final SqlPostProcessor postProcessor;

    AliasSqlGeneratorEngine(ColumnMappingRegistry registry) {
        this.registry = registry;
        this.transformer = new SelectLineTransformer(registry);

        this.segmentTransformer = new SqlSegmentTransformer(transformer);
        this.bareColumnClauseConverter = new BareColumnClauseConverter(registry);
        this.tableIdConverter = new TableIdConverter(registry);
        this.postProcessor = new SqlPostProcessor();
    }

    private static void warnUnsupportedSyntax(String sqlText, ConversionContext ctx, ConversionWarningSink sink) {
        if (sink == null || sqlText == null || sqlText.isEmpty()) return;
        String u = sqlText.toUpperCase();

        // Keep it conservative: only warn on known risky patterns.
        String[] patterns = {
                "CONNECT BY",
                "START WITH",
                "MODEL ",
                "PIVOT",
                "UNPIVOT",
                "MATCH_RECOGNIZE",
                "WITH RECURSIVE"
        };

        for (String p : patterns) {
            if (u.contains(p)) {
                sink.warn(new ConversionWarning(
                        WarningCode.UNSUPPORTED_SYNTAX_DETECTED,
                        ctx == null ? "" : ctx.getServiceClass(),
                        ctx == null ? "" : ctx.getNamespace(),
                        ctx == null ? "" : ctx.getSqlId(),
                        "unsupported/risky syntax detected",
                        p
                ));
            }
        }
    }

    String generate(String sqlText, Mode mode) {
        return generate(sqlText, mode, null, ConversionWarningSink.none());
    }

    String generate(String sqlText, Mode mode, ConversionContext ctx, ConversionWarningSink sink) {
        if (sqlText == null) return "";

        warnUnsupportedSyntax(sqlText, ctx, sink);

        // Resolve aliases on original SQL (used for param rename inference).
        Map<String, String> aliasTableMap0 = FromJoinAliasResolver.resolve(sqlText);

        Map<String, String> paramRenameMap =
                (mode == Mode.TOBE)
                        ? transformer.buildParamRenameMap(sqlText, aliasTableMap0)
                        : Collections.emptyMap();

        // 1) Transform all SELECT blocks (format + alias/comment rule).
        String out = segmentTransformer.transformAllSelectSegments(sqlText, mode, paramRenameMap, ctx, sink);

        if (mode == Mode.TOBE) {
            // 2) Convert the whole SQL (WHERE/ON/HAVING/GROUP/ORDER + DML areas included).
            Map<String, String> aliasTableMap = FromJoinAliasResolver.resolve(out);
            out = transformer.convertSqlFragmentToTobe(out, aliasTableMap, paramRenameMap);

            // 3) annotate INSERT/UPDATE column positions with /* tobeName */.
            out = transformer.annotateDml(out, aliasTableMap);

            // 3.1) Rename MyBatis "#{...}"/"${...}" parameter names to match TOBE column ids (lowerCamelCase).
            //      - best-effort: inferred from patterns like COL = #{param}
            //      - fallback: #{AUD_YR} -> #{audYr}
            // (removed duplicate renamer call; run once below)

            String before = out;

            // 기존
            // out = new MybatisBraceParamRenamer(registry).rename(out, aliasTableMap);

            String after = new MybatisBraceParamRenamer(registry).rename(before, aliasTableMap);

            // ✅ 변경 여부/변경 위치 로그
            if (!before.equals(after)) {
                int idx = firstDiffIndex(before, after);
//                System.out.println("[DEBUG] renamer CHANGED diffIndex=" + idx);
//                System.out.println("[DEBUG] before@diff=" + sliceAround(before, idx, 120));
//                System.out.println("[DEBUG] after @diff=" + sliceAround(after,  idx, 120));
            } else {
                // 정말 필요할 때만 켜도 됨
                // System.out.println("[DEBUG] renamer NO-CHANGE");
            }

            out = after;

            // 3.2) Convert qualified references in non-SELECT contexts too (WHERE/ON/SET/VALUES/...)
            //      - Also converts the qualifier if it's an actual table id (e.g., TBBADDED001M.AUD_YR)
            out = new QualifiedColumnRefConverter(registry).convert(out, aliasTableMap, ctx, sink);

            // 3.5) WHERE/ON/... bare columns fallback
            out = bareColumnClauseConverter.convertBareColumnsInClausesToTobe(out, ctx, sink);

            // 4) TOBE only: table id conversion
            out = tableIdConverter.convertTableIdsToTobe(out, ctx, sink);
        }

        return postProcessor.process(out);
    }

    private static int firstDiffIndex(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return (a.length() == b.length()) ? -1 : n;
    }

    private static String sliceAround(String s, int center, int width) {
        if (s == null) return "null";
        if (center < 0) center = 0;
        int half = Math.max(10, width / 2);
        int st = Math.max(0, center - half);
        int ed = Math.min(s.length(), center + half);
        return s.substring(st, ed).replace("\r", "\\r").replace("\n", "\\n");
    }
}