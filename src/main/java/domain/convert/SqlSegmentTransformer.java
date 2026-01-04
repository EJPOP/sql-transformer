package domain.convert;

import domain.convert.AliasSqlGenerator.Mode;
import domain.model.ConversionContext;
import domain.model.ConversionWarningSink;

import java.util.Map;

/**
 * Rewrites only SELECT-body parts for every SELECT statement found in the SQL string.
 *
 * <p>Key goal: do not break non-SELECT SQL by over-eager parsing.
 * We detect SELECT boundaries using a lightweight scanner that respects strings/comments and parentheses depth.
 */
final class SqlSegmentTransformer {

    private final SelectLineTransformer transformer;

    SqlSegmentTransformer(SelectLineTransformer transformer) {
        this.transformer = transformer;
    }

    /**
     * Legacy signature (no warnings).
     */
    String transformAllSelectSegments(String sql, Mode mode, Map<String, String> paramRenameMap) {
        return transformSegment(sql, mode, paramRenameMap, null, ConversionWarningSink.none());
    }

    /**
     * Extended signature (Task3): pass-through context + warning sink into SELECT transformation.
     */
    String transformAllSelectSegments(String sql,
                                      Mode mode,
                                      Map<String, String> paramRenameMap,
                                      ConversionContext ctx,
                                      ConversionWarningSink sink) {
        return transformSegment(sql, mode, paramRenameMap, ctx, sink == null ? ConversionWarningSink.none() : sink);
    }

    // ------------------------------------------------------------
    // Legacy-compatible implementation (extracted from old AliasSqlGenerator)
    // ------------------------------------------------------------

    private String transformSegment(String sql,
                                    Mode mode,
                                    Map<String, String> paramRenameMap,
                                    ConversionContext ctx,
                                    ConversionWarningSink sink) {
        if (sql == null || sql.isEmpty()) return sql;

        StringBuilder sb = new StringBuilder(sql.length() + 64);

        int i = 0;
        while (i < sql.length()) {
            SelectHit hit = findNextSelect(sql, i);
            if (hit == null) {
                sb.append(sql, i, sql.length());
                break;
            }

            sb.append(sql, i, hit.selectStart);

            int end = findSelectEnd(sql, hit.selectStart, hit.depthAtSelect);
            if (end <= hit.selectStart) {
                sb.append(sql, hit.selectStart, sql.length());
                break;
            }

            String stmt = sql.substring(hit.selectStart, end);
            String transformed = transformSelectStatement(stmt, mode, paramRenameMap, ctx, sink);
            sb.append(transformed);

            i = end;
        }

        return sb.toString();
    }

    private String transformSelectStatement(String stmt,
                                            Mode mode,
                                            Map<String, String> paramRenameMap,
                                            ConversionContext ctx,
                                            ConversionWarningSink sink) {
        int selectIdx = indexOfKeywordOutside(stmt, 0, "SELECT");
        if (selectIdx < 0) return stmt;

        int afterSelect = selectIdx + 6;
        int fromIdx = indexOfKeywordOutsideTopLevel(stmt, afterSelect, "FROM");
        if (fromIdx < 0) return stmt;

        String prefix = stmt.substring(0, afterSelect);
        String selectBody = stmt.substring(afterSelect, fromIdx);
        String rest = stmt.substring(fromIdx);

        // Recursively process nested SELECTs within select-body/rest.
        String selectBodyNested = transformSegment(selectBody, mode, paramRenameMap, ctx, sink);
        String restNested = transformSegment(rest, mode, paramRenameMap, ctx, sink);

        // Alias table map must be resolved on the FROM... part.
        var aliasTableMap = FromJoinAliasResolver.resolve(restNested);

        String newSelectBody = transformer.transformSelectBody(selectBodyNested, aliasTableMap, mode, ctx, sink);
        return prefix + newSelectBody + restNested;
    }

    private int indexOfKeywordOutsideTopLevel(String s, int start, String kw) {
        ScanState st = new ScanState();
        for (int i = 0; i < start && i < s.length(); i++) st.step(s, i);

        for (int i = start; i < s.length(); i++) {
            st.step(s, i);
            if (st.inStringOrComment()) continue;

            // main level only
            if (st.depth != 0) continue;

            if (isKeywordAt(s, i, kw)) return i;
        }
        return -1;
    }

    // ----------------------------
    // SELECT boundary detection
    // ----------------------------

    private SelectHit findNextSelect(String s, int start) {
        ScanState st = new ScanState();

        for (int i = 0; i < start && i < s.length(); i++) {
            st.step(s, i);
        }

        for (int i = start; i < s.length(); i++) {
            if (!st.step(s, i)) continue;

            if (isKeywordAt(s, i, "SELECT")) {
                return new SelectHit(i, st.depth);
            }
        }
        return null;
    }

    private int findSelectEnd(String s, int selectStart, int baseDepth) {
        ScanState st = new ScanState();

        for (int i = 0; i < selectStart && i < s.length(); i++) {
            st.step(s, i);
        }

        for (int i = selectStart + 6; i < s.length(); i++) {
            st.step(s, i);

            if (!st.inStringOrComment() && st.depth < baseDepth) {
                return i;
            }

            if (!st.inStringOrComment() && st.depth == baseDepth) {
                if (isKeywordAt(s, i, "UNION")
                        || isKeywordAt(s, i, "INTERSECT")
                        || isKeywordAt(s, i, "EXCEPT")
                        || isKeywordAt(s, i, "MINUS")) {
                    return i;
                }
                if (s.charAt(i) == ';') return i;
            }
        }
        return s.length();
    }

    private int indexOfKeywordOutside(String s, int start, String kw) {
        ScanState st = new ScanState();
        for (int i = 0; i < start && i < s.length(); i++) st.step(s, i);

        for (int i = start; i < s.length(); i++) {
            st.step(s, i);
            if (st.inStringOrComment()) continue;
            if (isKeywordAt(s, i, kw)) return i;
        }
        return -1;
    }

    private boolean isKeywordAt(String s, int idx, String kw) {
        int n = kw.length();
        if (idx < 0 || idx + n > s.length()) return false;

        if (idx > 0 && isWordChar(s.charAt(idx - 1))) return false;

        for (int i = 0; i < n; i++) {
            char a = s.charAt(idx + i);
            char b = kw.charAt(i);
            if (Character.toUpperCase(a) != Character.toUpperCase(b)) return false;
        }

        return idx + n >= s.length() || !isWordChar(s.charAt(idx + n));
    }

    private boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static final class SelectHit {
        final int selectStart;
        final int depthAtSelect;

        SelectHit(int selectStart, int depthAtSelect) {
            this.selectStart = selectStart;
            this.depthAtSelect = depthAtSelect;
        }
    }

    // ----------------------------
    // Scanner state
    // ----------------------------

    private static final class ScanState {
        int depth = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        /**
         * @return true if caller may evaluate current char for keywords (i.e., not in comment/string)
         */
        boolean step(String s, int i) {
            char c = s.charAt(i);
            char n = (i + 1 < s.length()) ? s.charAt(i + 1) : '\0';

            // line comment
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                return false;
            }

            // block comment
            if (inBlockComment) {
                if (c == '*' && n == '/') {
                    inBlockComment = false;
                }
                return false;
            }

            // strings
            if (inSingleQuote) {
                if (c == '\'' && n == '\'') {
                    // escaped ''
                    return false;
                }
                if (c == '\'') inSingleQuote = false;
                return false;
            }

            if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
                return false;
            }

            // enter comment/string
            if (c == '-' && n == '-') {
                inLineComment = true;
                return false;
            }
            if (c == '/' && n == '*') {
                inBlockComment = true;
                return false;
            }
            if (c == '\'') {
                inSingleQuote = true;
                return false;
            }
            if (c == '"') {
                inDoubleQuote = true;
                return false;
            }

            // parentheses
            if (c == '(') depth++;
            else if (c == ')') depth--;

            return true;
        }

        boolean inStringOrComment() {
            return inLineComment || inBlockComment || inSingleQuote || inDoubleQuote;
        }
    }
}