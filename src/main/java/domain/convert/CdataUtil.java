package domain.convert;

import java.util.function.Function;

/**
 * <![CDATA[ ... ]]> 유틸.
 *
 * <p>SQL 텍스트가 CDATA로 감싸져 있을 때, 내부 SQL만 변환/프리티파이하고
 * 원래의 CDATA 래퍼는 그대로 유지한다.</p>
 */
final class CdataUtil {

    private CdataUtil() {
    }

    static boolean isCdata(String raw) {
        return raw != null && raw.startsWith("<![CDATA[");
    }

    static String innerOf(String raw) {
        if (raw == null) return "";
        if (!isCdata(raw)) return raw;
        int start = raw.indexOf("<![CDATA[") + 9;
        int end = raw.lastIndexOf("]]>");
        if (end < start) return raw.substring(start);
        return raw.substring(start, end);
    }

    /**
     * CDATA이면 내부만 transformFn 적용 후 다시 감싼다.
     * CDATA가 아니면 그대로 transformFn을 적용한다.
     */
    static String transform(String raw, Function<String, String> transformFn) {
        if (raw == null) return null;
        if (transformFn == null) return raw;

        if (!isCdata(raw)) {
            return transformFn.apply(raw);
        }

        String inner = innerOf(raw);
        String converted = transformFn.apply(inner);

        // 원본 래퍼를 최대한 유지하되, 표준 형태로 재구성한다.
        return "<![CDATA[\n" + converted + "\n]]>";
    }
}
