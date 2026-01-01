package domain.output;

import domain.output.ServicePackageUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class ServicePackageUnitTest {

    @Test
    void should_extract_from_mapper_namespace_token9() {
        ServicePackageUnit u = ServicePackageUnit.fromRaw("AEPADR001EMapper.insertAudDataRqs");
        assertEquals("aepadr001", u.key());
        assertFalse(u.isUnknown());
    }

    @Test
    void should_extract_from_windows_path_top3folders() {
        ServicePackageUnit u = ServicePackageUnit.fromRaw("D:\\run\\systems\\oasys\\sqlmap\\aep\\adr\\001\\AEPADR001EMapper.xml");
        assertEquals("aepadr001", u.key());
    }

    @Test
    void should_extract_from_unix_path_top3folders() {
        ServicePackageUnit u = ServicePackageUnit.fromRaw("/sqlmap/aep/afu/001/AEPAFU001EMapper.xml");
        assertEquals("aepafu001", u.key());
    }

    @Test
    void should_return_unknown_when_not_match() {
        ServicePackageUnit u = ServicePackageUnit.fromRaw("no_match_here");
        assertTrue(u.isUnknown());
        assertEquals("unknown", u.key());
    }

    @Test
    void should_resolve_from_namespace_or_serviceClass() {
        String pkg = ServicePackageUnit.resolve(
                "oasys.migration.somepkg.AEPADR001Service",
                "aep/adr/001/AEPAFU001EMapper"
        );
        // namespace에 aep/adr/001이 있으니 aepadr001 쪽이 우선으로 잡혀야 함
        assertEquals("aepadr001", pkg);
    }
}