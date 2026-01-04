package domain.convert;

/**
 * Post-processing steps applied after main SQL transformations.
 */
final class SqlPostProcessor {

    /**
     * Collapse whitespace-only empty lines. Intended as a final safety net after formatting.
     *
     * <p>Examples of removed patterns: "\n\n", "\n   \n", "\r\n\r\n"</p>
     */
    static String squeezeBlankLines(String sql) {
        if (sql == null || sql.isEmpty()) return sql;

        // Normalize line endings to simplify processing.
        boolean hadCrlf = sql.contains("\r\n");
        String normalized = sql.replace("\r\n", "\n");

        // Remove any whitespace-only empty lines (one or many).
        normalized = normalized.replaceAll("\n[\t \f]*\n+", "\n");

        // Restore CRLF if the input looked like CRLF.
        if (hadCrlf) {
            normalized = normalized.replace("\n", "\r\n");
        }
        return normalized;
    }

    String process(String sql) {
        String out = sql;

        // Fix glued tokens BEFORE prettifier so clause detection works.
        out = GluedKeywordFixer.fixAfterSpecialTokens(out);

        if (Boolean.parseBoolean(System.getProperty("pretty", "false"))) {
            out = SqlPrettifier.format(out);
        }

        // Safety net: if prettifier glues again (rare), fix once more.
        out = GluedKeywordFixer.fixAfterSpecialTokens(out);

        // Remove accidental consecutive blank lines produced by prior steps.
        if (Boolean.parseBoolean(System.getProperty("squeezeBlankLines", "true"))) {
            out = squeezeBlankLines(out);
        }

        return out;
    }
}
