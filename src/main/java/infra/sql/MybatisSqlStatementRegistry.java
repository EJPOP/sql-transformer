package infra.sql;

import domain.convert.SqlStatement;

import java.nio.file.Path;
import java.util.List;

public class MybatisSqlStatementRegistry {

    private final MybatisMapperXmlIndex index = new MybatisMapperXmlIndex();
    private boolean initialized = false;

    private static String safe(Path p) {
        return p == null ? "" : p.toAbsolutePath()
                .toString();
    }

    public void initialize() {
        if (initialized) return;

        MybatisSqlsDirectoryScanner scanner = new MybatisSqlsDirectoryScanner();
        List<Path> xmls = scanner.scanAllSqlXml();

        System.out.println("[INIT] baseDir = " + safe(scanner.getResolvedBaseDir()));
        System.out.println("[INIT] sqlsDir = " + safe(scanner.getResolvedSqlsDir()));

        index.buildIndex(xmls);

        initialized = true;
        System.out.println("[INIT] SQL index size = " + index.size());
    }

    public SqlStatement get(String namespace, String sqlId) {
        return index.get(namespace, sqlId);
    }
}