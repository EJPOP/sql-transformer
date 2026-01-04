package domain.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extracts comment bodies from SQL segments so transformation can re-attach comments consistently.
 */
final class CommentExtractor {

    private static boolean onlyWhitespaceSinceLastLineBreak(String s) {
        if (s == null || s.isEmpty()) return true;
        int lastNl = Math.max(s.lastIndexOf('\n'), s.lastIndexOf('\r'));
        String tail = (lastNl >= 0) ? s.substring(lastNl + 1) : s;
        return tail.trim()
                .isEmpty();
    }

    private static String blockCommentBody(String commentToken) {
        if (commentToken == null) return "";
        String c = commentToken.trim();
        if (!c.startsWith("/*") || !c.endsWith("*/")) return c;
        return c.substring(2, c.length() - 2)
                .trim();
    }

    private static String lineCommentBody(String lineCommentToken) {
        if (lineCommentToken == null) return "";
        String c = lineCommentToken.trim();
        if (c.startsWith("--")) c = c.substring(2);
        return c.trim();
    }

    private static String rtrim(String s) {
        if (s == null || s.isEmpty()) return "";
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    /**
     * Extract standalone comment lines at the *start* of a comma-split segment.
     * - Only treats a block comment as standalone if it ends the current line
     * (i.e., followed by a newline before any other token).
     * - Line comments are always standalone.
     */
    Result extractLeadingStandaloneComments(String segment) {
        if (segment == null || segment.isEmpty()) return new Result("", new ArrayList<>());

        String s = segment;
        int i = 0;
        int n = s.length();
        List<String> comments = new ArrayList<>();

        while (i < n) {
            int wsStart = i;
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;

            if (i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) break;
                end += 2;

                // standalone only if a newline appears before any non-ws token after the comment
                int j = end;
                boolean sawNl = false;
                while (j < n) {
                    char ch = s.charAt(j);
                    if (ch == '\n' || ch == '\r') {
                        sawNl = true;
                        break;
                    }
                    if (!Character.isWhitespace(ch)) {
                        sawNl = false;
                        break;
                    }
                    j++;
                }
                if (!sawNl && j < n && !Character.isWhitespace(s.charAt(j))) {
                    // inline block comment; stop
                    i = wsStart;
                    break;
                }

                comments.add(blockCommentBody(s.substring(i, end)));
                i = end;
                continue;
            }

            if (i + 1 < n && s.charAt(i) == '-' && s.charAt(i + 1) == '-') {
                int end = i + 2;
                while (end < n && s.charAt(end) != '\n' && s.charAt(end) != '\r') end++;
                comments.add(lineCommentBody(s.substring(i, end)));
                i = end;
                continue;
            }

            i = wsStart;
            break;
        }

        String rest = (i < n) ? s.substring(i) : "";
        return new Result(rest, comments);
    }

    /**
     * Extract standalone comment lines at the *end* of a comma-split segment,
     * where the comment begins on its own line (only whitespace after the last newline).
     */
    Result extractTrailingStandaloneComments(String segment) {
        if (segment == null || segment.isEmpty()) return new Result("", new ArrayList<>());

        String s = segment;
        List<String> comments = new ArrayList<>();

        while (true) {
            String t = rtrim(s);
            if (t.isEmpty()) {
                s = t;
                break;
            }

            if (t.endsWith("*/")) {
                int start = t.lastIndexOf("/*");
                if (start >= 0) {
                    String before = t.substring(0, start);
                    if (onlyWhitespaceSinceLastLineBreak(before)) {
                        comments.add(0, blockCommentBody(t.substring(start)));
                        s = before;
                        continue;
                    }
                }
            }

            int dash = t.lastIndexOf("--");
            if (dash >= 0) {
                String before = t.substring(0, dash);
                if (onlyWhitespaceSinceLastLineBreak(before)) {
                    comments.add(0, lineCommentBody(t.substring(dash)));
                    s = before;
                    continue;
                }
            }

            break;
        }

        return new Result(s, comments);
    }

    /**
     * Extract trailing inline comments (block or line) at end of segment.
     * Collects multiple trailing comments (e.g. "... /*a* / /*b* /").
     */
    Result extractTrailingInlineComments(String segment) {
        if (segment == null || segment.isEmpty()) return new Result("", new ArrayList<>());

        String s = segment;
        List<String> comments = new ArrayList<>();

        while (true) {
            String t = rtrim(s);
            if (t.isEmpty()) {
                s = t;
                break;
            }

            if (t.endsWith("*/")) {
                int start = t.lastIndexOf("/*");
                if (start >= 0) {
                    comments.add(0, blockCommentBody(t.substring(start)));
                    s = t.substring(0, start);
                    continue;
                }
            }

            int dash = t.lastIndexOf("--");
            if (dash >= 0) {
                int nl = Math.max(t.lastIndexOf('\n'), t.lastIndexOf('\r'));
                if (nl < dash) {
                    comments.add(0, lineCommentBody(t.substring(dash)));
                    s = t.substring(0, dash);
                    continue;
                }
            }

            break;
        }

        return new Result(s, comments);
    }

    static final class Result {
        final String sql;
        final List<String> comments; // comment bodies

        Result(String sql, List<String> comments) {
            this.sql = (sql == null) ? "" : sql;
            this.comments = (comments == null) ? Collections.emptyList() : comments;
        }
    }

}
