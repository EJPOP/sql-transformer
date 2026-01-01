package cli;

import java.util.HashMap;

import java.util.Map;

import java.util.Objects;

import domain.convert.AliasSqlGenerator;

/** CLI argument parsing helpers. */
public final class CliArgParser {

    private CliArgParser() {}

    public static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    public static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    public static boolean parseBoolean(String s, boolean def) {
        if (s == null || s.isBlank()) return def;
        String v = s.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("y") || v.equals("yes");
    }

    /**
     * Presence-style flag.
     * <ul>
     *   <li>--noResult       => true</li>
     *   <li>--noResult=true  => true</li>
     *   <li>--noResult=false => false</li>
     * </ul>
     */
    public static boolean flag(Map<String, String> argv, String key) {
        if (argv == null || key == null) return false;
        if (!argv.containsKey(key)) return false;
        String raw = argv.get(key);
        if (raw == null || raw.isBlank()) return true;
        return parseBoolean(raw, true);
    }

    /**
     * Parse mode in a backward/forward compatible way.
     *
     * <p>Why: the enum constants have evolved (e.g. ASIS_ALIAS_TO_TOBE -> ASIS_SQL, TOBE_SQL -> TOBE).
     * This method tries common candidates and finally falls back to a safe default.</p>
     *
     * Default: TOBE mode (first available among TOBE / TOBE_SQL).
     */
    public static AliasSqlGenerator.Mode parseMode(String raw) {
        String u = (raw == null) ? "" : raw.trim().toUpperCase();

        // normalize some user-friendly variants
        if (u.equals("TOBE-SQL")) u = "TOBE_SQL";
        if (u.equals("ASIS-SQL")) u = "ASIS_SQL";

        boolean wantsAsis = u.startsWith("ASIS");
        boolean wantsTobe = u.startsWith("TOBE") || u.isBlank();

        // Candidates in priority order
        String[] candidates;
        if (wantsAsis) {
            candidates = new String[] {"ASIS", "ASIS_SQL", "ASIS_ALIAS_TO_TOBE", "ASIS_ALIAS"};
        } else if (wantsTobe) {
            candidates = new String[] {"TOBE", "TOBE_SQL"};
        } else {
            candidates = new String[] {u, "TOBE", "TOBE_SQL", "ASIS", "ASIS_SQL", "ASIS_ALIAS_TO_TOBE"};
        }

        for (String c : candidates) {
            AliasSqlGenerator.Mode m = tryEnum(c);
            if (m != null) return m;
        }

        // ultimate fallback: choose any TOBE-ish constant if present, else first constant.
        AliasSqlGenerator.Mode m = tryEnum("TOBE");
        if (m != null) return m;
        m = tryEnum("TOBE_SQL");
        if (m != null) return m;

        AliasSqlGenerator.Mode[] all = AliasSqlGenerator.Mode.values();
        return all.length > 0 ? all[0] : null;
    }

    private static AliasSqlGenerator.Mode tryEnum(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Enum.valueOf(AliasSqlGenerator.Mode.class, name.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    public static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        if (args == null) return m;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) continue;
            a = a.trim();
            if (!a.startsWith("--")) continue;

            String k;
            String v;

            int eq = a.indexOf('=');
            if (eq > 2) {
                k = a.substring(2, eq).trim();
                v = a.substring(eq + 1).trim();
            } else {
                k = a.substring(2).trim();
                v = "";
                if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("--")) {
                    v = args[i + 1].trim();
                    i++;
                }
            }

            if (!k.isEmpty()) m.put(k, v);
        }

        return m;
    }
}
