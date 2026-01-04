package domain.convert;

import domain.model.ConversionContext;
import domain.model.ConversionWarning;
import domain.model.ListConversionWarningSink;
import domain.model.WarningCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MybatisDynamicTagValidatorTest {

    @Test
    void validate_shouldNotWarn_whenDynamicTagsPreserved() {
        String original = "INSERT INTO T(A,\n" +
                "<if test=\"x != null\">B,</if>\n" +
                "C) VALUES(1,<if test=\"x != null\">2,</if>3)";

        // 변환 과정에서 테이블/컬럼만 바뀌고, <if> 블록은 유지된다고 가정
        String transformed = "INSERT INTO TB_T(A,\n" +
                "<if test=\"x != null\">B,</if>\n" +
                "C) VALUES(1,<if test=\"x != null\">2,</if>3)";

        List<ConversionWarning> warnings = new ArrayList<>();
        MybatisDynamicTagValidator v = new MybatisDynamicTagValidator();
        v.validate(original, transformed, new ConversionContext("S", "N", "ID"), new ListConversionWarningSink(warnings));

        assertTrue(warnings.isEmpty(), "No warnings expected, but got: " + warnings);
    }

    @Test
    void validate_shouldWarn_whenTagsLost() {
        String original = "SELECT 1<if test=\"x != null\"> AND A=#{x}</if>";
        String transformed = "SELECT 1 AND A=#{x}"; // <if> 유실

        List<ConversionWarning> warnings = new ArrayList<>();
        MybatisDynamicTagValidator v = new MybatisDynamicTagValidator();
        v.validate(original, transformed, new ConversionContext("S", "N", "ID"), new ListConversionWarningSink(warnings));

        assertTrue(warnings.stream().anyMatch(w -> w.getCode() == WarningCode.MYBATIS_TAG_LOST));
    }

    @Test
    void validate_shouldWarn_whenTagsUnbalanced() {
        String original = "SELECT 1<if test=\"x != null\"> AND A=#{x}</if>";
        String transformed = "SELECT 1<if test=\"x != null\"> AND A=#{x}"; // </if> 누락

        List<ConversionWarning> warnings = new ArrayList<>();
        MybatisDynamicTagValidator v = new MybatisDynamicTagValidator();
        v.validate(original, transformed, new ConversionContext("S", "N", "ID"), new ListConversionWarningSink(warnings));

        assertTrue(warnings.stream().anyMatch(w -> w.getCode() == WarningCode.MYBATIS_TAG_UNBALANCED));
    }
}
