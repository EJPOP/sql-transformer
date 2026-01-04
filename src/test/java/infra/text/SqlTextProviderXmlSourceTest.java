package infra.text;

import mybatis.SqlStatementRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * service_sql_xref.csv의 sql_text가 아니라, resources/systems/{system}/sqls 하위 XML을 통해
 * SQL 원문을 가져오는 경로를 검증한다.
 */
public class SqlTextProviderXmlSourceTest {

    @AfterEach
    void cleanupProps() {
        System.clearProperty("sqlsDir");
        System.clearProperty("oasys.migration.sqlsDir");
        System.clearProperty("baseDir");
        System.clearProperty("oasys.migration.baseDir");
        System.clearProperty("system");
        System.clearProperty("oasys.migration.system");
    }

    @Test
    void xmlOnlyProvider_usesXmlEvenWhenCsvSqlTextExists() throws Exception {
        Path sqlsDir = Files.createTempDirectory("sqls");

        String xml = """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <mapper namespace=\"com.test.Mapper\">
                  <select id=\"sel1\">
                    SELECT *
                    FROM TB_TEST
                    WHERE ID = #{id}
                  </select>
                </mapper>
                """;

        Path xmlFile = sqlsDir.resolve("TestMapper.xml");
        Files.writeString(xmlFile, xml);

        // scanner가 sqlsDir를 정확히 보도록 속성을 준다.
        System.setProperty("sqlsDir", sqlsDir.toString());

        SqlStatementRegistry registry = new SqlStatementRegistry();
        registry.initialize();

        XmlOnlySqlTextProvider provider = new XmlOnlySqlTextProvider(registry);

        var resolved = provider.resolve("com.test.Mapper", "sel1", "SELECT 1 FROM DUAL");

        assertNotNull(resolved.getSqlText());
        assertTrue(resolved.getSqlText().toUpperCase().contains("FROM TB_TEST"));
        assertFalse(resolved.isFallbackUsed());
    }

    @Test
    void xmlFirstProvider_fallsBackToCsvWhenXmlNotFound() {
        XmlFirstSqlTextProvider provider = new XmlFirstSqlTextProvider(null);

        var resolved = provider.resolve("com.test.Mapper", "missing", "SELECT 1");

        assertEquals("SELECT 1", resolved.getSqlText());
        assertTrue(resolved.isFallbackUsed());
    }
}
