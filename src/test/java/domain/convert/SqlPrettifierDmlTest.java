package domain.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlPrettifierDmlTest {

    @Test
    void format_insert_keeps_mybatis_dynamic_tags_and_indents_lists() {
        String sql = """
                INSERT INTO TBBADDED001M(AUD_YR,<if test=\"x != null\">SPVR_RANK_CD,</if>EPS_TASK_CD) VALUES(#{AUD_YR},<if test=\"x != null\">#{SPVR_RANK_CD},</if>#{EPS_TASK_CD})
                """;

        String out = SqlPrettifier.format(sql);

        // basic structure
        assertTrue(out.contains("INSERT INTO"), out);
        assertTrue(out.contains("VALUES"), out);

        // list indentation (2 spaces)
        assertTrue(out.contains("\n  AUD_YR,"), out);
        assertTrue(out.contains("\n  <if test=\"x != null\">"), out);
        assertTrue(out.contains("\n  SPVR_RANK_CD,"), out);
        assertTrue(out.contains("\n  EPS_TASK_CD"), out);
    }
}
