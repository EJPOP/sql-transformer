package domain.convert;

import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ConversionWarningSink;
import domain.model.WarningCode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 동적 태그(<if>, <foreach> 등)가 변환 과정에서 유실/훼손되지 않았는지
 * '가벼운' 문자열 기반으로 검증한다.
 *
 * <p>
 * 목적: 대규모 AST 파서 없이도, 치환/매핑 과정에서 태그가 통째로 사라지거나
 * 닫는 태그가 깨지는 케이스를 빠르게 탐지하여 리포트(warnings)로 남긴다.
 * </p>
 */
public final class MybatisDynamicTagValidator {

    // 대상 태그는 운영에서 자주 쓰는 것만(필요 시 확장)
    private static final String TAGS = "if|foreach|choose|when|otherwise|trim|where|set";
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "<\\s*(/)?\\s*(" + TAGS + ")\\b[^>]*>",
            Pattern.CASE_INSENSITIVE
    );

    // 태그 경계에서 콤마가 밖으로 빠지는 등, 흔한 훼손 패턴 탐지
    private static final Pattern SUSPICIOUS_BOUNDARY = Pattern.compile(
            "</\\s*(if|foreach|when|otherwise|trim|where|set)\\s*>\\s*,",
            Pattern.CASE_INSENSITIVE
    );

    private static boolean containsDynamicTag(String sql) {
        return TAG_PATTERN.matcher(sql)
                .find();
    }

    private static Map<String, Integer> countOpenTags(String sql) {
        Map<String, Integer> m = new HashMap<>();
        Matcher matcher = TAG_PATTERN.matcher(sql);
        while (matcher.find()) {
            String slash = matcher.group(1);
            String tag = safeLower(matcher.group(2));
            if (tag.isEmpty()) continue;
            // 닫는 태그는 제외 (open만 카운트)
            if (slash != null && !slash.isEmpty()) continue;
            m.put(tag, m.getOrDefault(tag, 0) + 1);
        }
        return m;
    }

    /**
     * 변환 SQL 내 동적 태그가 제대로 닫혀있는지 단순 스택으로 체크.
     *
     * @return imbalance message or null
     */
    private static String checkBalance(String sql) {
        Deque<String> stack = new ArrayDeque<>();
        Matcher matcher = TAG_PATTERN.matcher(sql);
        while (matcher.find()) {
            String slash = matcher.group(1);
            String tag = safeLower(matcher.group(2));
            if (tag.isEmpty()) continue;

            boolean closing = (slash != null && !slash.isEmpty());
            if (!closing) {
                stack.push(tag);
            } else {
                if (stack.isEmpty()) {
                    return "closing tag without opening: </" + tag + ">";
                }
                String top = stack.pop();
                if (!top.equals(tag)) {
                    return "tag mismatch: expected </" + top + "> but got </" + tag + ">";
                }
            }
        }
        if (!stack.isEmpty()) {
            return "unclosed tags: " + stack;
        }
        return null;
    }

    private static String snippetAround(String s, int pos, int maxLen) {
        if (s == null) return "";
        int start = Math.max(0, pos - maxLen / 2);
        int end = Math.min(s.length(), start + maxLen);
        String sub = s.substring(start, end);
        return (start > 0 ? "..." : "") + sub + (end < s.length() ? "..." : "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim()
                .isEmpty();
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    public void validate(String originalSqlText,
                         String transformedSql,
                         ConversionContext ctx,
                         ConversionWarningSink warningSink) {

        if (isBlank(originalSqlText) || isBlank(transformedSql) || ctx == null || warningSink == null) {
            return;
        }

        // 원문에 동적 태그가 없으면 검증 생략 (불필요 warn 방지)
        if (!containsDynamicTag(originalSqlText)) {
            return;
        }

        Map<String, Integer> origOpen = countOpenTags(originalSqlText);
        Map<String, Integer> outOpen = countOpenTags(transformedSql);

        // 1) 유실 탐지
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, Integer> e : origOpen.entrySet()) {
            String tag = e.getKey();
            int o = e.getValue();
            int t = outOpen.getOrDefault(tag, 0);
            if (o > 0 && t == 0) {
                lost.add(tag + "(orig=" + o + ", out=" + t + ")");
            }
        }
        if (!lost.isEmpty()) {
            warningSink.warn(new ConversionWarning(
                    WarningCode.MYBATIS_TAG_LOST,
                    ctx.getServiceClass(),
                    ctx.getNamespace(),
                    ctx.getSqlId(),
                    "MyBatis dynamic tag lost: " + String.join(", ", lost),
                    "origOpen=" + origOpen + ", outOpen=" + outOpen
            ));
        }

        // 2) 균형(열림/닫힘) 검증
        String imbalance = checkBalance(transformedSql);
        if (imbalance != null) {
            warningSink.warn(new ConversionWarning(
                    WarningCode.MYBATIS_TAG_UNBALANCED,
                    ctx.getServiceClass(),
                    ctx.getNamespace(),
                    ctx.getSqlId(),
                    "MyBatis dynamic tag unbalanced: " + imbalance,
                    "outOpen=" + outOpen
            ));
        }

        // 3) 경계 훼손 의심 패턴 (예: </if>, 콤마)
        Matcher m = SUSPICIOUS_BOUNDARY.matcher(transformedSql);
        if (m.find()) {
            String snippet = snippetAround(transformedSql, m.start(), 120);
            warningSink.warn(new ConversionWarning(
                    WarningCode.MYBATIS_TAG_BOUNDARY_SUSPICIOUS,
                    ctx.getServiceClass(),
                    ctx.getNamespace(),
                    ctx.getSqlId(),
                    "Suspicious MyBatis tag boundary (comma after closing tag)",
                    snippet
            ));
        }
    }
}
