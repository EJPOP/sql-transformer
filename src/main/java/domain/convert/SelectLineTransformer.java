package domain.convert;

import domain.mapping.ColumnMappingRegistry;
import domain.model.ConversionContext;
import domain.model.ConversionWarningSink;

import java.util.Map;

/**
 * Public entry-point.
 * Implementation is delegated to {@link SelectLineTransformerCore} to keep this API stable and small.
 */
public class SelectLineTransformer {

    private final SelectLineTransformerCore core;

    public SelectLineTransformer(ColumnMappingRegistry registry) {
        this.core = new SelectLineTransformerCore(registry);
    }

    public String transformSelectBody(String selectBody,
                                      Map<String, String> aliasTableMap,
                                      AliasSqlGenerator.Mode mode) {
        return core.transformSelectBody(selectBody, aliasTableMap, mode);
    }

    /**
     * Extended overload to allow the SELECT phase to emit warnings (Task3).
     *
     * <p>This keeps the legacy method intact while enabling richer reporting in CLI/API layers.</p>
     */
    public String transformSelectBody(String selectBody,
                                      Map<String, String> aliasTableMap,
                                      AliasSqlGenerator.Mode mode,
                                      ConversionContext ctx,
                                      ConversionWarningSink sink) {
        return core.transformSelectBody(selectBody, aliasTableMap, mode, ctx, sink);
    }

    public String convertSqlFragmentToTobe(String fragment,
                                           Map<String, String> aliasTableMap,
                                           Map<String, String> paramRenameMap) {
        return core.convertSqlFragmentToTobe(fragment, aliasTableMap, paramRenameMap);
    }

    public Map<String, String> buildParamRenameMap(String sql, Map<String, String> aliasTableMap) {
        return core.buildParamRenameMap(sql, aliasTableMap);
    }

    public String annotateDml(String sql, Map<String, String> aliasTableMap) {
        return core.annotateDml(sql, aliasTableMap);
    }
}