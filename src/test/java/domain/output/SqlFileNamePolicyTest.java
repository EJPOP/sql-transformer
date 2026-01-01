package domain.output;

import domain.output.SqlFileNamePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlFileNamePolicyTest {

    @Test
    void should_compact_namespace_with_slash_backslash_underscore() {
        assertEquals("aepafu001", SqlFileNamePolicy.extractMapperSimpleName("aep/afu/001"));
        assertEquals("aepafu001", SqlFileNamePolicy.extractMapperSimpleName("aep\\afu\\001"));
        assertEquals("aepafu001", SqlFileNamePolicy.extractMapperSimpleName("aep_afu_001"));
    }

    @Test
    void should_use_simple_name_when_namespace_is_fqcn_then_compact() {
        assertEquals("aepafu001", SqlFileNamePolicy.extractMapperSimpleName("com.foo.bar.aep/afu/001"));
        assertEquals("aepafu001", SqlFileNamePolicy.extractMapperSimpleName("x.y.aep_afu_001"));
    }
}
