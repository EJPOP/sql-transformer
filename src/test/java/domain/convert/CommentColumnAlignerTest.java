package domain.convert;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CommentColumnAlignerTest {

    @Test
    void dml_comments_should_stay_on_same_line_and_be_wrapped() {
        CommentColumnAligner aligner = new CommentColumnAligner(42);

        String out = aligner.renderDmlWithFixedCommentColumn(
                "ADT_YR",
                Arrays.asList("감사년도", "현행 주석")
        );

        // same line
        assertFalse(out.contains("\n"));
        assertFalse(out.contains("\r"));

        // both comments should exist on same line, wrapped as block comments
        assertTrue(out.contains("/* 감사년도 */"));
        assertTrue(out.contains("/* 현행 주석 */"));
        assertTrue(out.contains("/* 감사년도 */ /* 현행 주석 */"), out);
    }

    @Test
    void long_expression_should_use_single_space_before_comment() {
        CommentColumnAligner aligner = new CommentColumnAligner(42);

        // length 41 (>40)
        String base = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // 41
        assertEquals(41, base.length());

        String out = aligner.renderSelectWithFixedCommentColumn(
                base,
                Arrays.asList("긴표현"),
                0
        );

        // For long expressions, policy says: exactly one space before the first comment
        assertTrue(out.startsWith(base + " /*"), out);
    }
}
