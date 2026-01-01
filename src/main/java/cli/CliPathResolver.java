package cli;

import java.nio.file.Files;

import java.nio.file.Path;

import java.nio.file.Paths;

import java.util.Locale;

import java.util.Map;

/** CLI path/system resolver (baseDir / system / sqlsDir). */
public final class CliPathResolver {

    private CliPathResolver() {}

    public static final String PROP_BASE_DIR = "baseDir";
    public static final String PROP_SYSTEM   = "system";
    public static final String PROP_SQLS_DIR = "sqlsDir";
    public static void applyBaseDirPropertyIfPresent(Map<String, String> argv) {
        String bd = (argv == null) ? null : argv.get("baseDir");
        if (bd == null || bd.isBlank()) return;
        System.setProperty(PROP_BASE_DIR, bd.trim());
    }
    public static Path resolveBaseDir() {
        String bd = System.getProperty(PROP_BASE_DIR);
        if (bd != null && !bd.isBlank()) {
            return resolveAgainstUserDir(bd).toAbsolutePath().normalize();
        }
        return Paths.get(".").toAbsolutePath().normalize();
    }
    public static void ensureBaseDirProperty(Path baseDir) {
        if (System.getProperty(PROP_BASE_DIR) == null) {
            System.setProperty(PROP_BASE_DIR, baseDir.toAbsolutePath().normalize().toString());
        }
    }
    public static String resolveSystem(Map<String, String> argv) {
        String sys = trimToNull(System.getProperty(PROP_SYSTEM));
        if (sys != null) return sys;

        sys = (argv == null) ? null : trimToNull(argv.get("system"));
        if (sys != null) return sys;

        // heuristics: infer from input paths
        String inferred = inferSystemFromPaths(
                argv == null ? null : argv.get("callchain"),
                argv == null ? null : argv.get("mapping"),
                argv == null ? null : argv.get("out"),
                argv == null ? null : argv.get("result")
        );
        return (inferred != null) ? inferred : "oasys";
    }
    public static void ensureSystemProperty(String system) {
        if (System.getProperty(PROP_SYSTEM) == null && system != null && !system.isBlank()) {
            System.setProperty(PROP_SYSTEM, system.trim());
        }
    }
    public static Path resolveSqlsDir(Path baseDir, String system, Map<String, String> argv) {
        String fromProp = trimToNull(System.getProperty(PROP_SQLS_DIR));
        if (fromProp != null) return resolvePath(baseDir, fromProp);

        String fromArg = (argv == null) ? null : trimToNull(argv.get("sqlsDir"));
        if (fromArg != null) return resolvePath(baseDir, fromArg);

        // default: baseDir/systems/<system>/sqls
        return baseDir.resolve("systems")
                .resolve(system)
                .resolve("sqls")
                .toAbsolutePath()
                .normalize();
    }
    public static void ensureSqlsDirProperty(Path sqlsDir) {
        if (System.getProperty(PROP_SQLS_DIR) == null && sqlsDir != null) {
            System.setProperty(PROP_SQLS_DIR, sqlsDir.toAbsolutePath().normalize().toString());
        }
    }
    public static Path resolvePath(Path baseDir, String input) {
        if (input == null || input.isBlank()) return null;
        Path p = Paths.get(input.trim());
        if (!p.isAbsolute()) {
            if (baseDir != null) p = baseDir.resolve(p);
            else p = resolveAgainstUserDir(input.trim());
        }
        return p.toAbsolutePath().normalize();
    }
    public static Path resolveAgainstUserDir(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Path p = Paths.get(raw.trim());
        if (p.isAbsolute()) return p;
        return Paths.get(System.getProperty("user.dir")).resolve(p);
    }
    public static void validateFileExists(Path p, String label) {
        if (p == null) throw new IllegalArgumentException(label + " is null");
        if (!Files.exists(p)) throw new IllegalArgumentException(label + " not found: " + p);
    }

    /**
     * Resolve an input that can be either:
     * <ul>
     *   <li>a file path</li>
     *   <li>a directory path containing one of candidate filenames</li>
     *   <li>blank/null -> defaultDir is used, then candidates are searched</li>
     * </ul>
     */
    public static Path resolveCandidateFile(Path baseDir,
                                           String rawArg,
                                           Path defaultDir,
                                           String label,
                                           String... candidateFileNames) {
        Path root;

        if (rawArg == null || rawArg.isBlank()) {
            root = defaultDir;
        } else {
            root = resolvePath(baseDir, rawArg);
        }

        if (root == null) {
            throw new IllegalArgumentException(label + " is null");
        }

        // If user points to a file, use it directly.
        if (Files.exists(root) && Files.isRegularFile(root)) {
            return root.toAbsolutePath().normalize();
        }

        // Treat as directory (even if not yet exists) and search candidates.
        Path dir = root;
        if (!Files.exists(dir)) {
            // allow missing dir: we still resolve a candidate path for clear error message
            if (candidateFileNames != null && candidateFileNames.length > 0) {
                return dir.resolve(candidateFileNames[0]).toAbsolutePath().normalize();
            }
            return dir.toAbsolutePath().normalize();
        }

        if (!Files.isDirectory(dir)) {
            return dir.toAbsolutePath().normalize();
        }

        if (candidateFileNames != null) {
            for (String name : candidateFileNames) {
                if (name == null || name.isBlank()) continue;
                Path p = dir.resolve(name);
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    return p.toAbsolutePath().normalize();
                }
            }
        }

        // not found -> return first candidate under dir (for consistent error message)
        if (candidateFileNames != null && candidateFileNames.length > 0) {
            return dir.resolve(candidateFileNames[0]).toAbsolutePath().normalize();
        }
        return dir.toAbsolutePath().normalize();
    }
    public static void mkdirs(Path p) {
        if (p == null) return;
        try { Files.createDirectories(p); } catch (Exception ignored) {}
    }
    public static String inferSystemFromPaths(String... paths) {
        if (paths == null) return null;
        for (String p : paths) {
            String sys = inferSystemFromPath(p);
            if (sys != null) return sys;
        }
        return null;
    }
    public static String inferSystemFromPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.replace('\\', '/').toLowerCase(Locale.ROOT);
        // systems/<system>/... or .../<system>/sqls
        int idx = t.indexOf("systems/");
        if (idx >= 0) {
            String rest = t.substring(idx + "systems/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String s = rest.substring(0, slash);
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }
    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
