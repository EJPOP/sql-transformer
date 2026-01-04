package domain.convert;

import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarningSink;

import java.util.Map;
import java.util.Objects;

/**
 * Package-private orchestrator keeping the public entry-point small.
 * All heavy logic is delegated to dedicated components.
 */
final class SelectLineTransformerCore {

    /**
     * Comment start column relative to expression start.
     * - override: -DcommentCol=40 (or -Dcomment.col=40)
     */
    static final int COMMENT_COL_FROM_EXPR_START = readCommentCol();

    /**
     * If a mapping's TOBE column is empty (deleted), emit 'deleted column' comments instead of dropping silently.
     * - default: true
     * - disable: -DemitDeletedColumnComments=false
     */
    static final boolean EMIT_DELETED_COLUMN_COMMENTS =
            !"false".equalsIgnoreCase(System.getProperty("emitDeletedColumnComments", "true"));

    private final ColumnMappingRegistry registry;
    private final CommentColumnAligner commentAligner;
    private final CommentExtractor commentExtractor;
    private final ParamRenamer paramRenamer;
    private final SelectRenderer selectRenderer;
    private final DmlAnnotator dmlAnnotator;

    // ✅ qualified ref pre-pass converter
    private final QualifiedColumnRefConverter qualifiedColumnRefConverter;

    SelectLineTransformerCore(ColumnMappingRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commentAligner = new CommentColumnAligner(COMMENT_COL_FROM_EXPR_START);
        this.commentExtractor = new CommentExtractor();
        this.paramRenamer = new ParamRenamer(this.registry);
        this.selectRenderer = new SelectRenderer(
                this.registry, this.commentAligner, this.paramRenamer, this.commentExtractor,
                EMIT_DELETED_COLUMN_COMMENTS
        );
        this.dmlAnnotator = new DmlAnnotator(
                this.registry, this.commentAligner, this.commentExtractor,
                EMIT_DELETED_COLUMN_COMMENTS
        );

        this.qualifiedColumnRefConverter = new QualifiedColumnRefConverter(this.registry);
    }

    private static int readCommentCol() {
        String v = System.getProperty("commentCol");
        if (v == null || v.isBlank()) v = System.getProperty("comment.col");
        int def = 30;

        if (v == null || v.isBlank()) return def;

        try {
            int n = Integer.parseInt(v.trim());
            if (n < 10) n = 10;
            if (n > 200) n = 200;
            return n;
        } catch (Exception ignore) {
            return def;
        }
    }

    String transformSelectBody(String selectBody, Map<String, String> aliasTableMap, AliasSqlGenerator.Mode mode) {
        String pre = qualifiedColumnRefConverter.convert(selectBody, aliasTableMap, null, ConversionWarningSink.none());
        return selectRenderer.transformSelectBody(pre, aliasTableMap, mode, null, ConversionWarningSink.none());
    }

    String transformSelectBody(String selectBody,
                               Map<String, String> aliasTableMap,
                               AliasSqlGenerator.Mode mode,
                               ConversionContext ctx,
                               ConversionWarningSink sink) {
        ConversionWarningSink s = (sink == null) ? ConversionWarningSink.none() : sink;
        String pre = qualifiedColumnRefConverter.convert(selectBody, aliasTableMap, ctx, s);
        return selectRenderer.transformSelectBody(pre, aliasTableMap, mode, ctx, s);
    }

    String convertSqlFragmentToTobe(String fragment,
                                    Map<String, String> aliasTableMap,
                                    Map<String, String> paramRenameMap) {
        String pre = qualifiedColumnRefConverter.convert(fragment, aliasTableMap, null, ConversionWarningSink.none());
        return selectRenderer.convertSqlFragmentToTobe(pre, aliasTableMap, paramRenameMap);
    }

    Map<String, String> buildParamRenameMap(String sql, Map<String, String> aliasTableMap) {
        return paramRenamer.buildParamRenameMap(sql, aliasTableMap);
    }

    String annotateDml(String sql, Map<String, String> aliasTableMap) {
        String pre = qualifiedColumnRefConverter.convert(sql, aliasTableMap, null, ConversionWarningSink.none());
        return dmlAnnotator.annotateDml(pre, aliasTableMap);
    }
}
