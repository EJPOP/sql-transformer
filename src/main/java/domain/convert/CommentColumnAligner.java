package domain.convert;

import java.util.*;

/**
 * Aligns block comments to a fixed column, relative to expression start.
 */
final class CommentColumnAligner {

    private final int commentColFromExprStart;

    CommentColumnAligner(int commentColFromExprStart) {
        this.commentColFromExprStart = commentColFromExprStart;
    }

    int commentColFromExprStart() {
        return commentColFromExprStart;
    }

String renderSelectWithFixedCommentColumn(String base, List<String> comments, int leadingWidth) {
    String b = rtrim(base == null ? "" : base);
    if (comments == null || comments.isEmpty()) return b;

    List<String> cs = new ArrayList<>();
    for (String c : comments) {
        if (c == null) continue;
        String t = c.trim();
        if (!t.isEmpty()) cs.add(t);
    }
    if (cs.isEmpty()) return b;

    int targetCol;
    int pad;

    if (containsNewline(b)) {
        int indent = lastLineIndentLen(b);
        targetCol = indent + commentColFromExprStart;
        int curCol = lastLineLen(b);
        pad = targetCol - curCol;
    } else {
        targetCol = Math.max(0, leadingWidth) + commentColFromExprStart;
        int curAbs = Math.max(0, leadingWidth) + b.length();
        pad = targetCol - curAbs;
    }

    if (pad < 1) pad = 1;

    StringBuilder sb = new StringBuilder(b.length() + 64);
    sb.append(b).append(spaces(pad)).append(wrapBlockComment(cs.get(0)));

    for (int i = 1; i < cs.size(); i++) {
        sb.append("\n").append(spaces(targetCol)).append(wrapBlockComment(cs.get(i)));
    }

    return sb.toString();
}

/**
 * DML(INSERT/UPDATE/VALUES) 주석 정렬:
 * - "컬럼/값 시작점" 기준 commentColFromExprStart 칸에 /* 를 고정한다.
 * - 주석이 2개 이상이면:
 *   - 첫 주석은 기존 규칙대로 같은 줄에 정렬
 *   - 나머지는 다음 줄로 내려서 동일 COMMENT_COL에 정렬
 */
String renderDmlWithFixedCommentColumn(String base, String comment) {
    if (comment == null || comment.trim().isEmpty()) {
        return rtrim(base == null ? "" : base);
    }
    return renderDmlWithFixedCommentColumn(base, Collections.singletonList(comment));
}

String renderDmlWithFixedCommentColumn(String base, List<String> comments) {
    String b = rtrim(base == null ? "" : base);
    if (comments == null || comments.isEmpty()) return b;

    List<String> cs = new ArrayList<>();
    for (String c : comments) {
        if (c == null) continue;
        String t = c.trim();
        if (!t.isEmpty()) cs.add(t);
    }
    if (cs.isEmpty()) return b;

    int targetCol;
    int pad;

    if (containsNewline(b)) {
        int indent = lastLineIndentLen(b);
        targetCol = indent + commentColFromExprStart;
        int curCol = lastLineLen(b);
        pad = targetCol - curCol;
    } else {
        int exprStart = dmlExprStartCol(b); // 콤마가 있으면 콤마 뒤
        targetCol = exprStart + commentColFromExprStart;
        int curCol = lastLineLen(b);
        pad = targetCol - curCol;
    }

    if (pad < 1) pad = 1;

    StringBuilder sb = new StringBuilder(b.length() + 64);
    sb.append(b).append(spaces(pad)).append(wrapBlockComment(cs.get(0)));

    for (int i = 1; i < cs.size(); i++) {
        sb.append("\n").append(spaces(targetCol)).append(wrapBlockComment(cs.get(i)));
    }

    return sb.toString();
}

// expr start: indent or (indent + ", " 제거)
static int dmlExprStartCol(String s) {
    if (s == null) return 0;
    int lineStart = lastLineStartIndex(s);
    int indent = lastLineIndentLen(s);
    int p = lineStart + indent;

    if (p < s.length() && s.charAt(p) == ',') {
        p++;
        while (p < s.length()) {
            char ch = s.charAt(p);
            if (ch == ' ' || ch == '\t') { p++; continue; }
            break;
        }
        return p - lineStart;
    }
    return indent;
}

static int lastLineStartIndex(String s) {
    int p1 = s.lastIndexOf('\n');
    int p2 = s.lastIndexOf('\r');
    int p = Math.max(p1, p2);
    return (p < 0) ? 0 : (p + 1);
}

static String spaces(int n) {
    if (n <= 0) return "";
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) sb.append(' ');
    return sb.toString();
}

static boolean containsNewline(String s) {
    if (s == null || s.isEmpty()) return false;
    return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
}

static String wrapBlockComment(String body) {
    if (body == null) return "/* */";
    String b = body.trim();
    if (b.isEmpty()) return "/* */";

    // keep Oracle hint style if present: /*+ ... */
    if (b.startsWith("+")) {
        return "/*" + b + " */";
    }
    return "/* " + b + " */";
}

static String rtrim(String s) {
    if (s == null) return "";
    int e = s.length();
    while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
    return s.substring(0, e);
}


private static int lastLineLen(String s) {
    if (s == null) return 0;
    int n = s.length();
    int p1 = s.lastIndexOf('\n');
    int p2 = s.lastIndexOf('\r');
    int p = Math.max(p1, p2);
    return (p < 0) ? n : (n - (p + 1));
}

private static int lastLineIndentLen(String s) {
    if (s == null || s.isEmpty()) return 0;
    int p1 = s.lastIndexOf('\n');
    int p2 = s.lastIndexOf('\r');
    int p = Math.max(p1, p2);
    int start = (p < 0) ? 0 : (p + 1);

    int i = start;
    while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\t') {
            i++;
            continue;
        }
        break;
    }
    return i - start;
}
}
