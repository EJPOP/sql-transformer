package domain.convert;

final class SqlScan {
    final String s;
    int pos = 0;

    SqlScan(String s) {
        this.s = (s == null) ? "" : s;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    boolean hasNext() {
        return pos < s.length();
    }

    char peek() {
        return (pos < s.length()) ? s.charAt(pos) : '\0';
    }

    char read() {
        return (pos < s.length()) ? s.charAt(pos++) : '\0';
    }

    boolean peekWord(String kw) {
        int n = kw.length();
        if (pos + n > s.length()) return false;
        if (pos > 0 && isWordChar(s.charAt(pos - 1))) return false;

        for (int i = 0; i < n; i++) {
            if (Character.toUpperCase(s.charAt(pos + i)) != Character.toUpperCase(kw.charAt(i))) return false;
        }
        return pos + n >= s.length() || !isWordChar(s.charAt(pos + n));
    }

    // ✅ MERGE 경계: "WHEN MATCHED" / "WHEN NOT"만 종료로 인정 (CASE WHEN 보호)
    boolean peekMergeWhenBoundary() {
        int save = pos;
        if (!peekWord("WHEN")) return false;
        readWord(); // WHEN
        readSpaces();
        boolean ok = peekWord("MATCHED") || peekWord("NOT");
        pos = save;
        return ok;
    }

    String readWord() {
        int start = pos;
        while (pos < s.length() && isWordChar(s.charAt(pos))) pos++;
        return s.substring(start, pos);
    }

    String readSpaces() {
        int start = pos;
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        return s.substring(start, pos);
    }

    boolean peekIsLineComment() {
        return pos + 1 < s.length() && s.charAt(pos) == '-' && s.charAt(pos + 1) == '-';
    }

    boolean peekIsBlockComment() {
        return pos + 1 < s.length() && s.charAt(pos) == '/' && s.charAt(pos + 1) == '*';
    }

    boolean peekIsSingleQuotedString() {
        return pos < s.length() && s.charAt(pos) == '\'';
    }

    boolean peekIsDoubleQuotedString() {
        return pos < s.length() && s.charAt(pos) == '"';
    }

    boolean peekIsMyBatisParam() {
        if (pos >= s.length()) return false;
        char c = s.charAt(pos);
        if (c != '#' && c != '$') return false;

        int q = pos + 1;
        while (q < s.length() && Character.isWhitespace(s.charAt(q))) q++;
        return q < s.length() && s.charAt(q) == '{';
    }

    boolean peekIsCdata() {
        return s.startsWith("<![CDATA[", pos);
    }

    String readCdata() {
        if (!peekIsCdata()) return "";
        int start = pos;
        int end = s.indexOf("]]>", pos + 9);
        if (end < 0) {
            pos = s.length();
            return s.substring(start);
        }
        pos = end + 3;
        return s.substring(start, pos);
    }

    /**
     * MyBatis XML tag (e.g. <if>, </if>, <choose>, <when>, <otherwise>, <foreach>, <trim>, <where>, <set>, <bind>, <include> ...).
     *
     * We treat tags as an atomic token so that conversion/pretty-print does not break them.
     * Conservative to avoid misclassifying SQL operators like '<', '<=', '<>'.
     */
    boolean peekIsXmlTag() {
        if (pos >= s.length() || s.charAt(pos) != '<') return false;
        if (peekIsCdata()) return true;

        if (pos + 1 >= s.length()) return false;
        char next = s.charAt(pos + 1);

        // likely SQL operators: <=, <> (not tags)
        if (next == '=' || next == '>') return false;

        int p = pos + 1;
        if (s.charAt(p) == '/') p++;
        if (p >= s.length()) return false;

        int nameStart = p;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (Character.isLetter(c) || c == '_' || c == '-') {
                p++;
                continue;
            }
            break;
        }
        if (nameStart == p) return false;

        String name = s.substring(nameStart, p).toLowerCase();

        // common MyBatis dynamic tags + mapper tags
        return name.equals("if")
                || name.equals("choose")
                || name.equals("when")
                || name.equals("otherwise")
                || name.equals("foreach")
                || name.equals("trim")
                || name.equals("where")
                || name.equals("set")
                || name.equals("bind")
                || name.equals("include")
                || name.equals("sql")
                || name.equals("select")
                || name.equals("insert")
                || name.equals("update")
                || name.equals("delete");
    }

    /**
     * ✅ FIXED: quote-aware XML tag reader.
     * Do NOT stop at '>' inside quoted attribute values (e.g. <if test="A > 0">).
     */
    String readXmlTag() {
        if (peekIsCdata()) return readCdata();
        if (!peekIsXmlTag()) return "";

        int start = pos;
        boolean inSingle = false;
        boolean inDouble = false;

        while (pos < s.length()) {
            char c = read();

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (c == '>' && !inSingle && !inDouble) break;
        }
        return s.substring(start, Math.min(pos, s.length()));
    }

    /**
     * MyBatis legacy "#PARAM#" token (not "#{...}") used in 일부 SQL.
     * Distinct from peekIsMyBatisParam().
     */
    boolean peekIsHashToken() {
        if (pos >= s.length()) return false;
        if (s.charAt(pos) != '#') return false;

        // exclude #{...}
        int q = pos + 1;
        if (q < s.length() && s.charAt(q) == '{') return false;

        int next = s.indexOf('#', q);
        return next > q;
    }

    // Backward-compat alias
    boolean peekIsHashParam() {
        return peekIsHashToken();
    }

    String readMyBatisParam() {
        int start = pos;
        pos++; // # or $
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        if (pos < s.length() && s.charAt(pos) == '{') pos++;

        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '}') break;
        }
        return s.substring(start, pos);
    }

    String readDoubleQuotedString() {
        int start = pos;
        pos++; // "
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') break;
        }
        return s.substring(start, pos);
    }

    String readLineComment() {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '\n') break;
        }
        return s.substring(start, pos);
    }

    String readBlockComment() {
        int start = pos;
        pos += 2; // /*
        while (pos + 1 < s.length()) {
            if (s.charAt(pos) == '*' && s.charAt(pos + 1) == '/') {
                pos += 2;
                break;
            }
            pos++;
        }
        return s.substring(start, pos);
    }

    String readSingleQuotedString() {
        int start = pos;
        pos++; // '
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '\'') {
                // escaped ''
                if (pos < s.length() && s.charAt(pos) == '\'') {
                    pos++;
                    continue;
                }
                break;
            }
        }
        return s.substring(start, pos);
    }

    String readIdentifier() {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$') {
                pos++;
                continue;
            }
            break;
        }
        return s.substring(start, pos);
    }

    String readHashToken() {
        int start = pos;
        pos++; // #
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '#') break;
        }
        return s.substring(start, pos);
    }

    // Backward-compat alias
    String readHashParam() {
        return readHashToken();
    }

    String readParenBlock() {
        if (peek() != '(') return "";
        int start = pos;
        int depth = 0;

        while (pos < s.length()) {
            if (peekIsLineComment()) { readLineComment(); continue; }
            if (peekIsBlockComment()) { readBlockComment(); continue; }
            if (peekIsSingleQuotedString()) { readSingleQuotedString(); continue; }
            if (peekIsMyBatisParam()) { readMyBatisParam(); continue; }
            if (peekIsXmlTag()) { readXmlTag(); continue; } // ✅ 안전 추가

            char c = read();
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) break;
            }
        }

        return s.substring(start, pos);
    }

    String readUntilSetTerminator() {
        int start = pos;
        int depth = 0;

        while (pos < s.length()) {
            if (peekIsLineComment()) { readLineComment(); continue; }
            if (peekIsBlockComment()) { readBlockComment(); continue; }
            if (peekIsSingleQuotedString()) { readSingleQuotedString(); continue; }
            if (peekIsMyBatisParam()) { readMyBatisParam(); continue; }
            if (peekIsXmlTag()) { readXmlTag(); continue; } // ✅ 안전 추가

            if (depth == 0) {
                if (peekWord("WHERE") || peekWord("GROUP") || peekWord("ORDER") || peekWord("HAVING")) break;
                if (peekWord("DELETE") || peekMergeWhenBoundary()) break; // ✅ MERGE 경계
            }

            char c = read();
            if (c == '(') depth++;
            else if (c == ')') depth = Math.max(0, depth - 1);
        }

        return s.substring(start, pos);
    }

    /**
     * Look ahead: if next non-space token is '(' and inside the parens the first non-space/comment token is SELECT.
     */
    boolean peekParenStartsWithSelect() {
        int p0 = pos;
        int n = s.length();

        // skip spaces/comments
        while (p0 < n) {
            char c = s.charAt(p0);
            if (Character.isWhitespace(c)) { p0++; continue; }
            if (c == '-' && p0 + 1 < n && s.charAt(p0 + 1) == '-') {
                p0 += 2;
                while (p0 < n && s.charAt(p0) != '\n') p0++;
                continue;
            }
            if (c == '/' && p0 + 1 < n && s.charAt(p0 + 1) == '*') {
                p0 += 2;
                while (p0 + 1 < n) {
                    if (s.charAt(p0) == '*' && s.charAt(p0 + 1) == '/') { p0 += 2; break; }
                    p0++;
                }
                continue;
            }
            break;
        }

        if (p0 >= n || s.charAt(p0) != '(') return false;
        p0++;

        // inside: skip spaces/comments again
        while (p0 < n) {
            char c = s.charAt(p0);
            if (Character.isWhitespace(c)) { p0++; continue; }
            if (c == '-' && p0 + 1 < n && s.charAt(p0 + 1) == '-') {
                p0 += 2;
                while (p0 < n && s.charAt(p0) != '\n') p0++;
                continue;
            }
            if (c == '/' && p0 + 1 < n && s.charAt(p0 + 1) == '*') {
                p0 += 2;
                while (p0 + 1 < n) {
                    if (s.charAt(p0) == '*' && s.charAt(p0 + 1) == '/') { p0 += 2; break; }
                    p0++;
                }
                continue;
            }
            break;
        }

        int w0 = p0;
        while (p0 < n && (Character.isLetter(s.charAt(p0)) || s.charAt(p0) == '_')) p0++;
        if (w0 == p0) return false;

        String w = s.substring(w0, p0);
        return w.equalsIgnoreCase("SELECT");
    }

    /**
     * Look ahead: if next non-space token is '(' and inside the parens the first non-space/comment token is
     * SELECT or WITH.
     * <p>
     * This is conservative and only skips whitespaces and SQL comments.
     * Strings/MyBatis params/XML tags are not interpreted here because this is a look-ahead used by the
     * prettifier only.
     */
    boolean peekParenStartsWithSelectOrWith() {
        int p0 = pos;
        int n = s.length();

        // skip spaces/comments
        while (p0 < n) {
            char c = s.charAt(p0);
            if (Character.isWhitespace(c)) {
                p0++;
                continue;
            }
            if (c == '-' && p0 + 1 < n && s.charAt(p0 + 1) == '-') {
                p0 += 2;
                while (p0 < n && s.charAt(p0) != '\n') p0++;
                continue;
            }
            if (c == '/' && p0 + 1 < n && s.charAt(p0 + 1) == '*') {
                p0 += 2;
                while (p0 + 1 < n) {
                    if (s.charAt(p0) == '*' && s.charAt(p0 + 1) == '/') {
                        p0 += 2;
                        break;
                    }
                    p0++;
                }
                continue;
            }
            break;
        }

        if (p0 >= n || s.charAt(p0) != '(') return false;
        p0++;

        // inside: skip spaces/comments again
        while (p0 < n) {
            char c = s.charAt(p0);
            if (Character.isWhitespace(c)) {
                p0++;
                continue;
            }
            if (c == '-' && p0 + 1 < n && s.charAt(p0 + 1) == '-') {
                p0 += 2;
                while (p0 < n && s.charAt(p0) != '\n') p0++;
                continue;
            }
            if (c == '/' && p0 + 1 < n && s.charAt(p0 + 1) == '*') {
                p0 += 2;
                while (p0 + 1 < n) {
                    if (s.charAt(p0) == '*' && s.charAt(p0 + 1) == '/') {
                        p0 += 2;
                        break;
                    }
                    p0++;
                }
                continue;
            }
            break;
        }

        int w0 = p0;
        while (p0 < n && (Character.isLetter(s.charAt(p0)) || s.charAt(p0) == '_')) p0++;
        if (w0 == p0) return false;

        String w = s.substring(w0, p0);
        return w.equalsIgnoreCase("SELECT") || w.equalsIgnoreCase("WITH");
    }

    // Added for SqlPrettifier compatibility (alias of readSpaces)
    void readWhileWhitespace() {
        readSpaces();
    }
}
