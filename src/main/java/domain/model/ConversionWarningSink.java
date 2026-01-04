package domain.model;

/**
 * Sink for conversion warnings.
 *
 * <p>Why: warnings are produced across many internal components (converter, table-id replacer,
 * clause fallback, etc.). A simple sink allows us to collect them without coupling internals
 * to the CLI or the XLSX writer.</p>
 */
public interface ConversionWarningSink {

    static ConversionWarningSink none() {
        return NullConversionWarningSink.INSTANCE;
    }

    void warn(ConversionWarning warning);
}
