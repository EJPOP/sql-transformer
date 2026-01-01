package domain.convert;

import domain.convert.SqlTopLevelSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlTopLevelSplitterMyBatisTest {

    @Test
    void splitTopLevelByComma_doesNotSplitInsideMyBatisXmlTags_nestedIfForeachTrim() {
        String s = "A,\n" +
                "<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n" +
                "  <if test=\"x != null and x != ''\">\n" +
                "    B,\n" +
                "  </if>\n" +
                "  <foreach collection=\"list\" item=\"i\" open=\"\" separator=\",\" close=\"\">\n" +
                "    C,\n" +
                "  </foreach>\n" +
                "</trim>\n" +
                "D";

        List<String> parts = SqlTopLevelSplitter.splitTopLevelByComma(s);
        assertEquals(2, parts.size(), "Only the top-level comma after A should split");

        String second = parts.get(1);
        assertTrue(second.contains("<trim"));
        assertTrue(second.contains("<if"));
        assertTrue(second.contains("B,"));
        assertTrue(second.contains("</if>"));
        assertTrue(second.contains("<foreach"));
        assertTrue(second.contains("separator=\",\""));
        assertTrue(second.contains("C,"));
        assertTrue(second.contains("</foreach>"));
        assertTrue(second.contains("D"));

        // Ensure we didn't break the if/foreach blocks into separate list items
        assertFalse(parts.stream().anyMatch(p -> p.trim().equals("B")), "B must stay inside <if> block");
        assertFalse(parts.stream().anyMatch(p -> p.trim().equals("C")), "C must stay inside <foreach> block");
    }

    @Test
    void indexOfTopLevelEquals_ignoresEqualsInsideXmlTagAttributes() {
        String s = "<if test=\"x == 1\">x</if> A=1";
        int idx = SqlTopLevelSplitter.indexOfTopLevelEquals(s);
        assertTrue(idx >= 0);
        assertEquals(s.indexOf("A=1") + 1, idx, "Should find '=' in A=1, not in <if test=...>");
    }
}
