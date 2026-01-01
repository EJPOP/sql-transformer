package infra.output;

import infra.output.AliasSqlFileWriter;
import infra.output.FileSqlOutputWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileSqlOutputWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void should_write_sql_under_serviceClass_namespace_and_sqlId_filename() throws Exception {
        // ✅ 생성자 변경 반영
        FileSqlOutputWriter w = new FileSqlOutputWriter(new AliasSqlFileWriter());

        Path out = tempDir.resolve("output");
        String serviceClass = "com.example.service.AEPAFU001ServiceImpl";
        String namespace = "AEPAFU001EMapper";
        String sqlId = "AEPAFU001ChangeStaApvRqsIdByApvRqsId";
        String sql = "-- test\nSELECT 1";

        w.write(out, serviceClass, namespace, sqlId, sql);

        Path expected = out
                .resolve("AEPAFU001ServiceImpl")
                .resolve("AEPAFU001EMapper")
                .resolve("AEPAFU001ChangeStaApvRqsIdByApvRqsId.sql");

        assertTrue(Files.exists(expected), "expected file not found: " + expected);
        String content = Files.readString(expected);
        assertTrue(content.contains("SELECT 1"));
    }
}
