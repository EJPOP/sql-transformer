package domain.convert;
/** Post-processing steps applied after main SQL transformations. */
final class SqlPostProcessor {

    String process(String sql) {
        String out = sql;

        // Fix glued tokens BEFORE prettifier so clause detection works.
        out = GluedKeywordFixer.fixAfterSpecialTokens(out);

        if (Boolean.parseBoolean(System.getProperty("pretty", "false"))) {
            out = SqlPrettifier.format(out);
        }

        // Safety net: if prettifier glues again (rare), fix once more.
        out = GluedKeywordFixer.fixAfterSpecialTokens(out);

        return out;
    }
}
