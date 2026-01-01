package infra.callchain;

import domain.callchain.ServiceSqlCall;
import infra.callchain.ServiceSqlXrefLoader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceSqlXrefLoaderNormalizationTest {

    @Test
    void should_normalize_sqlId_fqcn_into_namespace_plus_simple_id() throws Exception {
        Path temp = Files.createTempDirectory("xref");
        Path csv = temp.resolve("callchain.csv");

        String header = String.join(",",
                "service",
                "service_method",
                "mapper_file",
                "namespace",
                "id",
                "sql_text");

        String nsSimple = "AEPADR001EMapper";
        String fqcn = "kr.go.bai.eip.aep.adr.dao.AEPADR001EMapper.insertAudDataRqs";
        String row = String.join(",",
                "AEPADR001EServiceImpl",
                "insertAudDataRqs",
                "AEPADR001EMapper.xml",
                nsSimple,
                fqcn,
                "SELECT 1");

        Files.writeString(csv, header + "\n" + row + "\n", StandardCharsets.UTF_8);

        ServiceSqlXrefLoader loader = new ServiceSqlXrefLoader();
        List<ServiceSqlCall> calls = loader.load(csv);

        assertEquals(1, calls.size());
        ServiceSqlCall c = calls.get(0);
        assertEquals("kr.go.bai.eip.aep.adr.dao.AEPADR001EMapper", c.getMapperNamespace());
        assertEquals("insertAudDataRqs", c.getSqlId());
    }

    @Test
    void should_normalize_namespace_path_separators() throws Exception {
        Path temp = Files.createTempDirectory("xref");
        Path csv = temp.resolve("callchain.csv");

        String header = "service,service_method,mapper_file,namespace,id,sql_text";
        String nsPath = "kr/go/bai/eip/aep/adr/dao/AEPADR001EMapper";
        String row = "SVC,method,Mapper.xml," + nsPath + ",AEPADR001EMapper.selectX,SELECT 1";

        Files.writeString(csv, header + "\n" + row + "\n", StandardCharsets.UTF_8);

        ServiceSqlXrefLoader loader = new ServiceSqlXrefLoader();
        List<ServiceSqlCall> calls = loader.load(csv);

        assertEquals(1, calls.size());
        ServiceSqlCall c = calls.get(0);
        assertEquals("kr.go.bai.eip.aep.adr.dao.AEPADR001EMapper", c.getMapperNamespace());
        assertEquals("selectX", c.getSqlId());
    }
}
