package domain.model;
/** No-op warning sink. */
final class NullConversionWarningSink implements ConversionWarningSink {

    static final NullConversionWarningSink INSTANCE = new NullConversionWarningSink();

    private NullConversionWarningSink() {
    }

    @Override
    public void warn(ConversionWarning warning) {
        // no-op
    }
}
