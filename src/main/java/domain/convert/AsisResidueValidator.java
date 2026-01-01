package domain.convert;

import java.util.ArrayList;

import java.util.HashSet;

import java.util.List;

import java.util.Set;

import domain.mapping.ColumnMappingRegistry;

import domain.model.ConversionContext;

import domain.model.ConversionWarning;

import domain.model.ConversionWarningSink;

import domain.model.WarningCode;

/**
 * AS-IS residue validation.
 *
 * <p>After conversion, scan the output SQL and detect remaining AS-IS table/column ids.
 * This is a pragmatic safety net because full guarantees are hard with dynamic SQL and nested aliasing.</p>
 *
 * <p><b>Excluded (preserved) regions</b>:
 */
public final class AsisResidueValidator {

    private final ColumnMappingRegistry registry;

    public AsisResidueValidator(ColumnMappingRegistry registry) {
        this.registry = registry;
    }

    public void validate(String convertedSql,
                         ConversionContext ctx,
                         ConversionWarningSink sink,
                         boolean failFast) {
        if (convertedSql == null || convertedSql.isBlank()) return;
        if (registry == null) return;

        String scrubbed = scrubPreservedRegions(convertedSql);
        Set<String> tokens = extractIdentifierTokensUpper(scrubbed);

        Set<String> asisTables = registry.getAsisTableIdsUpper();
        Set<String> asisCols   = registry.getAsisColumnIdsUpper();

        List<String> hitTables = new ArrayList<>(8);
        List<String> hitCols   = new ArrayList<>(16);

        for (String t : tokens) {
            if (asisTables.contains(t)) hitTables.add(t);
            if (asisCols.contains(t)) hitCols.add(t);
        }

        if (hitTables.isEmpty() && hitCols.isEmpty()) return;

        String svc = (ctx == null) ? "" : ctx.getServiceClass();
        String ns  = (ctx == null) ? "" : ctx.getNamespace();
        String id  = (ctx == null) ? "" : ctx.getSqlId();

        String msg = "AS-IS residue detected: tables=" + hitTables.size() + ", columns=" + hitCols.size();
        String detail = "tables=" + summarize(hitTables, 20) + " | columns=" + summarize(hitCols, 30);

        if (failFast) {
            throw new IllegalStateException(msg + " (" + detail + ")");
        }

        ConversionWarningSink s = (sink == null) ? ConversionWarningSink.none() : sink;
        s.warn(new ConversionWarning(WarningCode.ASIS_RESIDUE_DETECTED, svc, ns, id, msg, detail));
    }

    private static String summarize(List<String> list, int limit) {
        if (list == null || list.isEmpty()) return "[]";
        int n = Math.min(limit, list.size());
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i));
        }
        if (list.size() > n) sb.append(", ...");
        sb.append(']');
        return sb.toString();
    }

    /**
     * Replace excluded regions with spaces to keep positions and prevent false hits.
     */
    static String scrubPreservedRegions(String sql) {
        StringBuilder out = new StringBuilder(sql.length());

        boolean inSQuote = false;
        boolean inDQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inTag = false;
        boolean inCdata = false;
        boolean inMybatisParam = false; // #{...} or ${...}

        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char n = (i + 1 < sql.length()) ? sql.charAt(i + 1) : '\0';

            // CDATA start
            if (!inSQuote && !inDQuote && !inLineComment && !inBlockComment && !inCdata) {
                if (sql.startsWith("<![CDATA[", i)) {
                    inCdata = true;
                    out.append("         "); // length 9
                    i += 9;
                    continue;
                }
            }
            // CDATA end
            if (inCdata) {
                if (sql.startsWith("]]>", i)) {
                    inCdata = false;
                    out.append("   ");
                    i += 3;
                    continue;
                }
                out.append(' ');
                i++;
                continue;
            }

            // line comment start
            if (!inSQuote && !inDQuote && !inLineComment && !inBlockComment && !inTag && c == '-' && n == '-') {
                inLineComment = true;
                out.append("  ");
                i += 2;
                continue;
            }
            // line comment end
            if (inLineComment) {
                out.append(' ');
                if (c == '\n') inLineComment = false;
                i++;
                continue;
            }

            // block comment start
            if (!inSQuote && !inDQuote && !inBlockComment && !inTag && c == '/' && n == '*') {
                inBlockComment = true;
                out.append("  ");
                i += 2;
                continue;
            }
            // block comment end
            if (inBlockComment) {
                out.append(' ');
                if (c == '*' && n == '/') {
                    out.append(' ');
                    i += 2;
                    inBlockComment = false;
                    continue;
                }
                i++;
                continue;
            }

            // MyBatis param start
            if (!inSQuote && !inDQuote && !inTag && !inMybatisParam && ((c == '#' || c == '$') && n == '{')) {
                inMybatisParam = true;
                out.append("  ");
                i += 2;
                continue;
            }
            if (inMybatisParam) {
                out.append(' ');
                if (c == '}') inMybatisParam = false;
                i++;
                continue;
            }

            // XML tag (MyBatis) start/end: very simple scanning to exclude <...>
            if (!inSQuote && !inDQuote && !inTag && c == '<') {
                inTag = true;
                out.append(' ');
                i++;
                continue;
            }
            if (inTag) {
                out.append(' ');
                if (c == '>') inTag = false;
                i++;
                continue;
            }

            // quoted strings
            if (!inDQuote && c == '\'' && !inLineComment && !inBlockComment) {
                inSQuote = !inSQuote;
                out.append(' ');
                i++;
                continue;
            }
            if (!inSQuote && c == '"' && !inLineComment && !inBlockComment) {
                inDQuote = !inDQuote;
                out.append(' ');
                i++;
                continue;
            }
            if (inSQuote || inDQuote) {
                out.append(' ');
                i++;
                continue;
            }

            out.append(c);
            i++;
        }

        return out.toString();
    }

    static Set<String> extractIdentifierTokensUpper(String sql) {
        Set<String> out = new HashSet<>();
        if (sql == null || sql.isEmpty()) return out;

        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (isIdentChar(c)) {
                int j = i + 1;
                while (j < sql.length() && isIdentChar(sql.charAt(j))) j++;

                if (j - i >= 3) {
                    String token = sql.substring(i, j).toUpperCase();
                    out.add(token);
                }
                i = j;
                continue;
            }
            i++;
        }
        return out;
    }

    private static boolean isIdentChar(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    public static final class AsisResidueFailFastException extends RuntimeException {
        public AsisResidueFailFastException(String message) {
            super(message);
        }
    }
}
