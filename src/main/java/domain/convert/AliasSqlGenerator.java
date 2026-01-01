package domain.convert;

import domain.mapping.ColumnMappingRegistry;

import domain.model.ConversionContext;

import domain.model.ConversionWarningSink;


/**
 * Backward compatible facade.
 *
 * <p>Why: the original {@code AliasSqlGenerator} had too many responsibilities
 * (segment scanning, keyword glue-fixing, table/column conversions, formatting toggles).
 * We extracted the heavy logic into {@link AliasSqlGeneratorEngine} so that:
 * <ul>
 *   <li>public API remains stable</li>
 *   <li>internals can be modularized further without touching callers</li>
 * </ul>
 */
public class AliasSqlGenerator {

    public enum Mode {
        ASIS,
        TOBE
    }

    private final AliasSqlGeneratorEngine engine;

    public AliasSqlGenerator(ColumnMappingRegistry registry) {
        this.engine = new AliasSqlGeneratorEngine(registry);
    }

    /** Preserve public API */
    public String generate(String sqlText, Mode mode) {
        return engine.generate(sqlText, mode);
    }

    /**
     * Extended API for CLI/reporting to collect warnings with proper attribution.
     *
     * <p>Kept as an overload to avoid breaking existing callers.</p>
     */
    public String generate(String sqlText, Mode mode, ConversionContext ctx, ConversionWarningSink sink) {
        return engine.generate(sqlText, mode, ctx, sink == null ? ConversionWarningSink.none() : sink);
    }
}