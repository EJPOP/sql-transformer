package domain.output;

import java.util.Locale;

import java.util.Objects;

import java.util.regex.Matcher;

import java.util.regex.Pattern;

/**
 * Output folder key generator.
 * Requirement: "상위 3개 폴더의 조합" => e.g. aep + adr + 001 => aepadr001
 */
public final class ServicePackageUnit {

    public static final ServicePackageUnit UNKNOWN = new ServicePackageUnit("unknown");

    // ex) AEPADR001, aepadr001
    private static final Pattern TOKEN_9 = Pattern.compile("(?i)(?<![a-z0-9])([a-z]{6}\\d{3})(?!\\d)");

    // ex) aep / adr / 001
    private static final Pattern TOKEN_3LET = Pattern.compile("(?i)^[a-z]{3}$");
    private static final Pattern TOKEN_3DIG = Pattern.compile("^\\d{3}$");

    // split by path/namespace separators (\, /, ., _, $, -)
    private static final Pattern SPLIT = Pattern.compile("[\\\\/._$-]+");

    private final String key; // ex) aepadr001

    private ServicePackageUnit(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean isUnknown() {
        return "unknown".equalsIgnoreCase(key);
    }

    /**
     * Derive package key from raw string:
     * - mapper file path
     * - mapper namespace
     * - queryId, etc.
     */
    public static ServicePackageUnit fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }

        // 1) direct 9-length token (AEPADR001)
        String tokenFromParts = findTokenFrom3Parts(raw);
        if (tokenFromParts != null) {
            return new ServicePackageUnit(tokenFromParts);
        }

        // 2) direct 9-length token (AEPADR001)
        String token9 = findToken9(raw);
        if (token9 != null) {
            return new ServicePackageUnit(token9);
        }

        return UNKNOWN;
    }

    private static String findToken9(String raw) {
        Matcher m = TOKEN_9.matcher(raw);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String findTokenFrom3Parts(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        String[] parts = SPLIT.split(normalized, -1);

        // scan consecutive triplets: (3 letters)(3 letters)(3 digits)
        for (int i = 0; i + 2 < parts.length; i++) {
            String a = parts[i];
            String b = parts[i + 1];
            String c = parts[i + 2];

            if (TOKEN_3LET.matcher(a).matches()
                    && TOKEN_3LET.matcher(b).matches()
                    && TOKEN_3DIG.matcher(c).matches()) {
                return a + b + c;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServicePackageUnit that)) return false;
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    /**
     * Backward-compatible API used by FileSqlOutputWriter.
     * Tries to resolve package unit from (namespace, serviceClass).
     */
    public static String resolve(String serviceClass, String namespace) {
        // 1) namespace first
        ServicePackageUnit u1 = fromRaw(namespace);
        if (!u1.isUnknown()) {
            return u1.key();
        }

        // 2) then serviceClass
        ServicePackageUnit u2 = fromRaw(serviceClass);
        if (!u2.isUnknown()) {
            return u2.key();
        }

        // 3) fallback: combine
        String combined = (serviceClass == null ? "" : serviceClass) + " " + (namespace == null ? "" : namespace);
        ServicePackageUnit u3 = fromRaw(combined);
        return u3.key(); // unknown이면 unknown 그대로 반환
    }
}
