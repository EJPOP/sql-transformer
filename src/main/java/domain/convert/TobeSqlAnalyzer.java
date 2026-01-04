package domain.convert;

import domain.model.TobeDmlParamRow;
import domain.model.TobeSelectOutputRow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Derives extra TOBE metadata from converted SQL:
 * - SELECT output column ids (lowerCamel)
 * - DML parameter names (lowerCamel)
 */
public final class TobeSqlAnalyzer {

    private TobeSqlAnalyzer() {
    }

    public static void collectTobeSelectOutputs(String tobeSql,
                                                String serviceClass,
                                                String namespace,
                                                String sqlId,
                                                List<TobeSelectOutputRow> out) {
        if (tobeSql == null || tobeSql.isBlank()) return;
        if (!isLeadingKeyword(tobeSql, "SELECT")) return;

        String selectBody = extractTopLevelSelectBody(tobeSql);
        if (selectBody == null || selectBody.isBlank()) return;

        List<String> items = splitTopLevelByComma(selectBody);
        int seq = 0;

        for (String raw : items) {
            String item = (raw == null) ? "" : raw.trim();
            if (item.isEmpty()) continue;

            String trailingComment = extractTrailingBlockComment(item);
            String exprNoComment = (trailingComment != null) ? stripTrailingBlockComment(item).trim() : item;

            ParsedAlias pa = parseAlias(exprNoComment);
            String expr = pa.exprOnly.trim();
            String alias = pa.alias;

            String inferredFromExpr = firstColumnIdInExpression(expr);
            String outputName;

            if (alias != null && !alias.isBlank()) {
                if (inferredFromExpr != null && !looksLikeColumnId(alias)) {
                    outputName = inferredFromExpr;
                } else if (looksLikeColumnId(inferredFromExpr) && looksLikeColumnId(alias)
                        && !alias.equalsIgnoreCase(inferredFromExpr)) {
                    outputName = inferredFromExpr;
                } else {
                    outputName = alias;
                }
            } else {
                if (inferredFromExpr != null) outputName = inferredFromExpr;
                else outputName = lastIdentifierPart(expr);
            }

            if (outputName == null || outputName.isBlank()) continue;

            String lc = toLowerCamel(outputName);
            out.add(new TobeSelectOutputRow(
                    serviceClass, namespace, sqlId, ++seq, outputName, lc, expr, trailingComment
            ));
        }
    }

    public static void collectTobeDmlParams(String tobeSql,
                                            String serviceClass,
                                            String namespace,
                                            String sqlId,
                                            List<TobeDmlParamRow> out) {
        if (tobeSql == null || tobeSql.isBlank()) return;

        boolean isInsert = isLeadingKeyword(tobeSql, "INSERT");
        boolean isUpdate = isLeadingKeyword(tobeSql, "UPDATE");
        boolean isDelete = isLeadingKeyword(tobeSql, "DELETE");
        if (!(isInsert || isUpdate || isDelete)) return;

        String dmlType = isInsert ? "INSERT" : (isUpdate ? "UPDATE" : "DELETE");

        LinkedHashSet<String> params = extractHashParams(tobeSql);
        if (params.isEmpty()) return;

        int seq = 0;
        for (String p : params) {
            String name = normalizeParamName(p);
            if (name == null || name.isBlank()) continue;

            String lc = toLowerCamel(name);
            out.add(new TobeDmlParamRow(
                    serviceClass, namespace, sqlId, ++seq, dmlType, name, lc
            ));
        }
    }

    private static boolean isLeadingKeyword(String sql, String kw) {
        String s = stripLeadingSpaceAndComments(sql);
        if (s.isEmpty()) return false;
        int n = kw.length();
        if (s.length() < n) return false;

        for (int i = 0; i < n; i++) {
            if (Character.toUpperCase(s.charAt(i)) != Character.toUpperCase(kw.charAt(i))) return false;
        }
        if (s.length() > n) {
            char next = s.charAt(n);
            return !isWordChar(next);
        }
        return true;
    }

    private static String stripLeadingSpaceAndComments(String sql) {
        if (sql == null) return "";
        int i = 0;
        int n = sql.length();

        while (i < n) {
            while (i < n && Character.isWhitespace(sql.charAt(i))) i++;

            if (i + 1 < n && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') i++;
                continue;
            }

            if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n) {
                    if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    i++;
                }
                continue;
            }

            break;
        }

        return (i >= n) ? "" : sql.substring(i);
    }

    private static String extractTopLevelSelectBody(String sql) {
        String s = stripLeadingSpaceAndComments(sql);
        int n = s.length();

        int pos = indexOfKeywordTopLevel(s, 0, "SELECT");
        if (pos < 0) return null;

        int afterSelect = pos + 6;
        while (afterSelect < n && Character.isWhitespace(s.charAt(afterSelect))) afterSelect++;

        int fromPos = indexOfKeywordTopLevel(s, afterSelect, "FROM");
        if (fromPos < 0) return null;

        return s.substring(afterSelect, fromPos)
                .trim();
    }

    private static int indexOfKeywordTopLevel(String s, int start, String kw) {
        int n = s.length();
        int depth = 0;
        boolean inStr = false;

        int i = Math.max(0, start);
        while (i < n) {
            char c = s.charAt(i);

            if (!inStr) {
                if (i + 1 < n && s.charAt(i) == '-' && s.charAt(i + 1) == '-') {
                    i += 2;
                    while (i < n && s.charAt(i) != '\n') i++;
                    continue;
                }
                if (i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < n) {
                        if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                            i += 2;
                            break;
                        }
                        i++;
                    }
                    continue;
                }
            }

            if (c == '\'') {
                if (inStr) {
                    if (i + 1 < n && s.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inStr = false;
                    i++;
                    continue;
                } else {
                    inStr = true;
                    i++;
                    continue;
                }
            }

            if (inStr) {
                i++;
                continue;
            }

            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                i++;
                continue;
            }

            if (depth == 0 && matchesKeywordAt(s, i, kw)) return i;
            i++;
        }

        return -1;
    }

    private static boolean matchesKeywordAt(String s, int i, String kw) {
        int n = s.length();
        int k = kw.length();
        if (i + k > n) return false;

        if (i > 0 && isWordChar(s.charAt(i - 1))) return false;

        for (int j = 0; j < k; j++) {
            if (Character.toUpperCase(s.charAt(i + j)) != Character.toUpperCase(kw.charAt(j))) return false;
        }

        return i + k >= n || !isWordChar(s.charAt(i + k));
    }

    private static List<String> splitTopLevelByComma(String s) {
        return splitTopLevelByComma0(s);
    }

    private static List<String> splitTopLevelByComma0(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inStr = false;

        int i = 0;
        int n = s.length();
        while (i < n) {
            if (!inStr) {
                if (i + 1 < n && s.charAt(i) == '-' && s.charAt(i + 1) == '-') {
                    int start = i;
                    i += 2;
                    while (i < n && s.charAt(i) != '\n') i++;
                    cur.append(s, start, i);
                    continue;
                }
                if (i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                    int start = i;
                    i += 2;
                    while (i + 1 < n) {
                        if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                            i += 2;
                            break;
                        }
                        i++;
                    }
                    cur.append(s, start, i);
                    continue;
                }
            }

            char c = s.charAt(i);

            if (c == '\'') {
                if (inStr) {
                    if (i + 1 < n && s.charAt(i + 1) == '\'') {
                        cur.append("''");
                        i += 2;
                        continue;
                    }
                    inStr = false;
                    cur.append(c);
                    i++;
                    continue;
                } else {
                    inStr = true;
                    cur.append(c);
                    i++;
                    continue;
                }
            }

            if (!inStr) {
                if (c == '(') depth++;
                else if (c == ')') depth = Math.max(0, depth - 1);

                if (c == ',' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    i++;
                    continue;
                }
            }

            cur.append(c);
            i++;
        }

        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static ParsedAlias parseAlias(String expr) {
        String t = (expr == null) ? "" : expr.trim();
        if (t.isEmpty()) return new ParsedAlias("", null);

        if (t.endsWith(";")) t = t.substring(0, t.length() - 1)
                .trim();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)^(.*)\\bAS\\b\\s+([A-Z0-9_]{1,120})\\s*$")
                .matcher(t);
        if (m.find()) {
            return new ParsedAlias(m.group(1)
                    .trim(), m.group(2)
                    .trim());
        }

        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("^(.*?)(?:\\s+)([A-Z0-9_]{1,120})\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(t);
        if (m2.find()) {
            String before = m2.group(1)
                    .trim();
            String last = m2.group(2)
                    .trim();

            if (!before.isEmpty() && !endsWithOperator(before)) {
                return new ParsedAlias(before, last);
            }
        }

        return new ParsedAlias(t, null);
    }

    private static boolean endsWithOperator(String s) {
        String t = (s == null) ? "" : s.trim();
        if (t.isEmpty()) return false;
        char c = t.charAt(t.length() - 1);
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == '=' || c == '<' || c == '>';
    }

    private static String extractTrailingBlockComment(String t) {
        if (t == null) return null;
        String s = t.trim();
        if (!s.endsWith("*/")) return null;

        int p = s.lastIndexOf("/*");
        if (p < 0) return null;

        String c = s.substring(p + 2, s.length() - 2)
                .trim();
        return c.isEmpty() ? null : c;
    }

    private static String stripTrailingBlockComment(String t) {
        if (t == null) return null;
        String s = t;
        int end = s.lastIndexOf("*/");
        int start = s.lastIndexOf("/*");
        if (start >= 0 && end > start && end == s.length() - 2) {
            return s.substring(0, start)
                    .trim();
        }
        return t;
    }

    private static boolean looksLikeColumnId(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        boolean hasUnderscore = t.indexOf('_') >= 0;
        boolean allUpperOrDigitOrUnderscore = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!(Character.isUpperCase(c) || Character.isDigit(c) || c == '_')) {
                allUpperOrDigitOrUnderscore = false;
                break;
            }
        }
        return hasUnderscore && allUpperOrDigitOrUnderscore;
    }

    private static String firstColumnIdInExpression(String expr) {
        if (expr == null) return null;
        String u = expr.toUpperCase();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b([A-Z0-9_]{1,30})\\s*\\.\\s*([A-Z0-9_]{1,60})\\b")
                .matcher(u);
        if (m.find()) {
            return m.group(2);
        }

        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(?i)\\b([A-Z0-9_]{2,80})\\b")
                .matcher(u);
        while (m2.find()) {
            String tok = m2.group(1);
            if (isSqlKeyword(tok)) continue;
            if (looksLikeColumnId(tok)) return tok;
        }
        return null;
    }

    private static String lastIdentifierPart(String expr) {
        if (expr == null) return null;
        String t = expr.trim();
        if (t.isEmpty()) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b([A-Z0-9_]{1,30})\\s*\\.\\s*([A-Z0-9_]{1,60})\\b(?!.*\\b([A-Z0-9_]{1,30})\\s*\\.\\s*([A-Z0-9_]{1,60})\\b)")
                .matcher(t);
        if (m.find()) return m.group(2);

        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(?i)\\b([A-Z0-9_]{2,80})\\b(?!.*\\b([A-Z0-9_]{2,80})\\b)")
                .matcher(t);
        if (m2.find()) return m2.group(1)
                .toUpperCase();

        return null;
    }

    private static LinkedHashSet<String> extractHashParams(String sql) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (sql == null || sql.isEmpty()) return out;

        int n = sql.length();
        int i = 0;
        boolean inStr = false;

        while (i < n) {
            char c = sql.charAt(i);

            if (!inStr) {
                if (i + 1 < n && sql.charAt(i) == '-' && sql.charAt(i + 1) == '-') {
                    i += 2;
                    while (i < n && sql.charAt(i) != '\n') i++;
                    continue;
                }
                if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < n) {
                        if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                            i += 2;
                            break;
                        }
                        i++;
                    }
                    continue;
                }
            }

            if (c == '\'') {
                if (inStr) {
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    inStr = false;
                    i++;
                    continue;
                } else {
                    inStr = true;
                    i++;
                    continue;
                }
            }

            if (inStr) {
                i++;
                continue;
            }

            if (c == '#') {
                int start = i;
                i++;
                while (i < n && sql.charAt(i) != '#') i++;
                if (i < n && sql.charAt(i) == '#') {
                    String token = sql.substring(start, i + 1);
                    out.add(token);
                    i++;
                    continue;
                }
                break;
            }

            i++;
        }

        return out;
    }

    private static String normalizeParamName(String hashToken) {
        if (hashToken == null) return null;
        String t = hashToken.trim();
        if (t.length() < 3) return null;
        if (!t.startsWith("#") || !t.endsWith("#")) return null;

        String inner = t.substring(1, t.length() - 1)
                .trim();
        if (inner.isEmpty()) return null;

        int dot = inner.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < inner.length()) inner = inner.substring(dot + 1);

        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
            else break;
        }

        String name = sb.toString();
        return name.isEmpty() ? null : name.toUpperCase();
    }

    private static String toLowerCamel(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";

        t = t.replace('-', '_');

        String[] parts = t.split("_+");
        StringBuilder out = new StringBuilder(t.length());

        boolean first = true;
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;

            String lower = p.toLowerCase(Locale.ROOT);

            if (first) {
                out.append(lower);
                first = false;
            } else {
                out.append(Character.toUpperCase(lower.charAt(0)));
                if (lower.length() > 1) out.append(lower.substring(1));
            }
        }

        if (out.length() == 0) return t;
        return out.toString();
    }

    private static boolean isSqlKeyword(String t) {
        if (t == null) return true;
        String u = t.toUpperCase(Locale.ROOT);
        return u.equals("SELECT") || u.equals("FROM") || u.equals("WHERE") || u.equals("AND") || u.equals("OR")
                || u.equals("CASE") || u.equals("WHEN") || u.equals("THEN") || u.equals("ELSE") || u.equals("END")
                || u.equals("NVL") || u.equals("DECODE") || u.equals("SUM") || u.equals("MAX") || u.equals("MIN")
                || u.equals("COUNT") || u.equals("DISTINCT") || u.equals("AS") || u.equals("IN") || u.equals("IS")
                || u.equals("NULL") || u.equals("NOT") || u.equals("LIKE") || u.equals("ON") || u.equals("JOIN")
                || u.equals("LEFT") || u.equals("RIGHT") || u.equals("INNER") || u.equals("OUTER")
                || u.equals("GROUP") || u.equals("ORDER") || u.equals("BY") || u.equals("HAVING")
                || u.equals("INSERT") || u.equals("UPDATE") || u.equals("DELETE") || u.equals("MERGE")
                || u.equals("INTO") || u.equals("VALUES") || u.equals("SET");
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static final class ParsedAlias {
        final String exprOnly;
        final String alias;

        ParsedAlias(String exprOnly, String alias) {
            this.exprOnly = exprOnly;
            this.alias = alias;
        }
    }
}