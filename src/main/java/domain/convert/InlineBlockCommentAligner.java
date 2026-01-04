package domain.convert;

import java.util.ArrayList;
import java.util.List;

/**
 * 인라인 블록 주석(trailing block comment) 정렬.
 *
 * <p>SQL 파서 수준으로 주석을 재배치하지 않고, "라인 단위"로만 정리한다.</p>
 */
final class InlineBlockCommentAligner {

    private InlineBlockCommentAligner() {
    }

    static String alignTrailingBlockComments(String sql) {
        return alignTrailingBlockComments(sql, Integer.MAX_VALUE, 90);
    }

    /**
     * 기존 호환: 항상 특정 컬럼에 맞춰 정렬.
     */
    static String alignTrailingBlockComments(String sql, int alignColumn) {
        return alignTrailingBlockComments(sql, Integer.MAX_VALUE, alignColumn);
    }

    /**
     * 정렬 규칙
     * <p>
     * - "표현식"(주석 앞 텍스트) 길이가 shortThreshold 이하이면 주석 시작 컬럼을 alignColumn에 맞춤
     * - shortThreshold를 초과하면 한 칸만 띄우고 주석을 붙임
     * <p>
     * alignColumn은 1-based 컬럼(사람 기준)이다.
     */
    static String alignTrailingBlockComments(String sql, int shortThreshold, int alignColumn) {
        if (sql == null || sql.isBlank()) return sql;

        int threshold = Math.max(0, shortThreshold);
        int commentStartIndex = Math.max(0, alignColumn - 1); // 1-based -> 0-based

        List<String> lines = splitPreserveNewline(sql);
        StringBuilder out = new StringBuilder(sql.length() + 64);

        for (String raw : lines) {
            String ln = stripNewline(raw);

            // 공백만 있는 라인은 공백을 제거하고 개행만 유지한다.
            // (여기서 공백 찌꺼기를 제거해 두면 후처리 단계의 빈줄 축약이 더 안정적이다.)
            if (ln.trim()
                    .isEmpty()) {
                out.append(extractNewline(raw));
                continue;
            }

            if (shouldSkipLine(ln)) {
                out.append(raw);
                continue;
            }

            int idx = indexOfTrailingBlockComment(ln);
            if (idx < 0) {
                out.append(raw);
                continue;
            }

            String before = rtrim(ln.substring(0, idx));
            String comment = ln.substring(idx)
                    .trim();

            String rebuilt;
            if (before.length() <= threshold && before.length() < commentStartIndex) {
                rebuilt = before + " ".repeat(commentStartIndex - before.length()) + comment;
            } else {
                rebuilt = before + " " + comment;
            }

            out.append(rebuilt);
            out.append(extractNewline(raw)); // 원본 개행 유지
        }

        return out.toString();
    }

    private static boolean shouldSkipLine(String ln) {
        if (ln == null) return true;
        String t = ln.trim();
        if (t.isEmpty()) return true;

        // XML / CDATA wrapper lines
        if (t.startsWith("<") || t.startsWith("<![CDATA[") || t.startsWith("]]>") || t.startsWith("</")) {
            return true;
        }

        // whole-line comments
        if (t.startsWith("--") || (t.startsWith("/*") && t.endsWith("*/"))) {
            return true;
        }

        // oracle hint-ish: /*+ ... */  or  /* + ... */
        return t.startsWith("/*+") || t.startsWith("/* +");
    }

    // trailing block comment 시작 인덱스 찾기
    // - 라인 끝이 */ 로 끝나야 함
    // - 마지막 /* 를 comment start로 간주
    // - 오라클 힌트(/*+ ...)는 제외
    private static int indexOfTrailingBlockComment(String ln) {
        if (ln == null) return -1;
        String t = rtrim(ln);
        if (!t.endsWith("*/")) return -1;

        int start = t.lastIndexOf("/*");
        if (start < 0) return -1;

        if (t.startsWith("/*+", start) || t.startsWith("/* +", start)) return -1;

        return start;
    }

    private static String rtrim(String s) {
        if (s == null || s.isEmpty()) return s;
        int i = s.length() - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') {
                i--;
                continue;
            }
            break;
        }
        return s.substring(0, i + 1);
    }

    private static List<String> splitPreserveNewline(String sql) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '\n') {
                lines.add(sql.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < sql.length()) lines.add(sql.substring(start));
        return lines;
    }

    private static String stripNewline(String raw) {
        if (raw.endsWith("\r\n")) return raw.substring(0, raw.length() - 2);
        if (raw.endsWith("\n")) return raw.substring(0, raw.length() - 1);
        return raw;
    }

    private static String extractNewline(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.endsWith("\r\n")) return "\r\n";
        if (raw.endsWith("\n")) return "\n";
        return "";
    }
}
