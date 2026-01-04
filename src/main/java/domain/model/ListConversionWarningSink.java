package domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * List-backed sink with best-effort de-duplication.
 *
 * <p>We deduplicate by (code|service|namespace|sqlId|message|detail) to avoid exploding rows
 * when the same issue is hit multiple times while scanning tokens.</p>
 */
public final class ListConversionWarningSink implements ConversionWarningSink {

    private final List<ConversionWarning> target;
    private final Set<String> seen = new HashSet<>(256);

    public ListConversionWarningSink(List<ConversionWarning> target) {
        this.target = target;
    }

    private static String key(ConversionWarning w) {
        return safe(w.getCode() == null ? "" : w.getCode()
                .name()) + "|"
                + safe(w.getServiceClass()) + "|"
                + safe(w.getNamespace()) + "|"
                + safe(w.getSqlId()) + "|"
                + safe(w.getMessage()) + "|"
                + safe(w.getDetail());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public void warn(ConversionWarning warning) {
        if (warning == null || target == null) return;
        String k = key(warning);
        if (seen.add(k)) {
            target.add(warning);
        }
    }
}
