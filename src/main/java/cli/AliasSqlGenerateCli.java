package cli;

import app.AliasSqlGenerateCliApp;


/**
 * CLI entrypoint facade.
 *
 * <p>The original implementation grew too large (option parsing, path resolution,
 * transformation heuristics, reporting helpers). The logic was moved to
 * {@link AliasSqlGenerateCliApp} to keep this class small and to enable testing.</p>
 *
 * <p>NOTE: This class name and package are intentionally preserved so existing
 * run scripts / documentation do not break.</p>
 */
public class AliasSqlGenerateCli {

    public static void main(String[] args) throws Exception {
        AliasSqlGenerateCliApp.main(args);
    }
}