package infra.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans mapper XML files under the resolved sqls directory.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>System property <b>oasys.migration.sqlsDir</b> (absolute or relative to baseDir)</li>
 *   <li>Default: <b>{baseDir}/systems/{system}/sqls</b></li>
 *   <li>Fallback for local dev: <b>{baseDir}/src/main/resources/sqls</b></li>
 * </ol>
 */
final class SqlsDirectoryScanner {

    private static final String PROP_BASE_DIR = "oasys.migration.baseDir";
    private static final String PROP_SYSTEM = "oasys.migration.system";
    private static final String PROP_SQLS_DIR = "oasys.migration.sqlsDir";

    private final Path resolvedBaseDir;
    private final Path resolvedSqlsDir;

    SqlsDirectoryScanner() {
        this.resolvedBaseDir = resolveBaseDir();
        this.resolvedSqlsDir = resolveSqlsDir(this.resolvedBaseDir);
    }

    private static Path resolveBaseDir() {
        String bd = System.getProperty(PROP_BASE_DIR);
        Path p;
        if (bd != null && !bd.isBlank()) {
            p = Paths.get(bd.trim());
            if (!p.isAbsolute()) {
                p = Paths.get(System.getProperty("user.dir"))
                        .resolve(p);
            }
        } else {
            p = Paths.get(".");
            if (!p.isAbsolute()) p = Paths.get(System.getProperty("user.dir"))
                    .resolve(p);
        }
        return p.toAbsolutePath()
                .normalize();
    }

    private static Path resolveSqlsDir(Path baseDir) {
        String fromProp = System.getProperty(PROP_SQLS_DIR);
        if (fromProp != null && !fromProp.isBlank()) {
            return resolveAgainstBaseDir(baseDir, fromProp.trim());
        }

        String system = System.getProperty(PROP_SYSTEM);
        if (system == null || system.isBlank()) system = "oasys";
        Path candidate = baseDir.resolve("systems")
                .resolve(system)
                .resolve("sqls")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(candidate)) return candidate;

        // Dev fallback: allow running directly from the repo without an external systems/... folder.
        Path dev = baseDir.resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("sqls")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(dev)) return dev;

        return candidate;
    }

    private static Path resolveAgainstBaseDir(Path baseDir, String raw) {
        Path p = Paths.get(raw);
        if (!p.isAbsolute()) p = baseDir.resolve(p);
        return p.toAbsolutePath()
                .normalize();
    }

    List<Path> scanAllSqlXml() {
        if (resolvedSqlsDir == null) return List.of();
        if (!Files.exists(resolvedSqlsDir)) return List.of();

        List<Path> out = new ArrayList<>(1024);
        try (Stream<Path> s = Files.walk(resolvedSqlsDir)) {
            s.filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName()
                            .toString()
                            .toLowerCase()
                            .endsWith(".xml"))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan sql xmls under: " + resolvedSqlsDir, e);
        }
        out.sort(Comparator.comparing(Path::toString));
        return out;
    }

    Path getResolvedBaseDir() {
        return resolvedBaseDir;
    }

    Path getResolvedSqlsDir() {
        return resolvedSqlsDir;
    }
}
