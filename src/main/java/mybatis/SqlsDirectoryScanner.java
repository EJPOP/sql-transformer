package mybatis;

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
 *   <li>System property <b>oasys.migration.sqlsDir</b> or <b>sqlsDir</b> (absolute or relative to baseDir)</li>
 *   <li>Default candidates (first existing wins)
 *     <ul>
 *       <li><b>{baseDir}/src/main/resources/systems/{system}/sqls</b></li>
 *       <li><b>{baseDir}/resources/systems/{system}/sqls</b></li>
 *       <li><b>{baseDir}/systems/{system}/sqls</b></li>
 *     </ul>
 *   </li>
 * </ol>
 */
final class SqlsDirectoryScanner {

    // 기존(legacy) prefix + 현재 CLI에서 쓰는 짧은 키 모두 허용
    private static final String PROP_BASE_DIR = "oasys.migration.baseDir";
    private static final String PROP_SYSTEM = "oasys.migration.system";
    private static final String PROP_SQLS_DIR = "oasys.migration.sqlsDir";

    private static final String PROP_BASE_DIR_SHORT = "baseDir";
    private static final String PROP_SYSTEM_SHORT = "system";
    private static final String PROP_SQLS_DIR_SHORT = "sqlsDir";

    private final Path resolvedBaseDir;
    private final Path resolvedSqlsDir;

    SqlsDirectoryScanner() {
        this.resolvedBaseDir = resolveBaseDir();
        this.resolvedSqlsDir = resolveSqlsDir(this.resolvedBaseDir);
    }

    private static Path resolveBaseDir() {
        String bd = firstNonBlank(
                System.getProperty(PROP_BASE_DIR),
                System.getProperty(PROP_BASE_DIR_SHORT)
        );
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
        String fromProp = firstNonBlank(
                System.getProperty(PROP_SQLS_DIR),
                System.getProperty(PROP_SQLS_DIR_SHORT)
        );
        if (fromProp != null && !fromProp.isBlank()) {
            return resolveAgainstBaseDir(baseDir, fromProp.trim());
        }

        String system = firstNonBlank(
                System.getProperty(PROP_SYSTEM),
                System.getProperty(PROP_SYSTEM_SHORT)
        );
        if (system == null || system.isBlank()) system = "oasys";

        // 1) repo/dev: src/main/resources/systems/<system>/sqls
        Path dev = baseDir.resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("systems")
                .resolve(system)
                .resolve("sqls")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(dev)) return dev;

        // 2) externalized resources: resources/systems/<system>/sqls
        Path resources = baseDir.resolve("resources")
                .resolve("systems")
                .resolve(system)
                .resolve("sqls")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(resources)) return resources;

        // 3) legacy: systems/<system>/sqls
        return baseDir.resolve("systems")
                .resolve(system)
                .resolve("sqls")
                .toAbsolutePath()
                .normalize();
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
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
