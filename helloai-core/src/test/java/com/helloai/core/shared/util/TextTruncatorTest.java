package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TextTruncator 单测（P-1 防御层）：行边界回退截断的边界行为。
 */
@DisplayName("TextTruncator 行边界截断")
class TextTruncatorTest {

    @Test
    @DisplayName("未超限 → 原样返回（不截断）")
    void shouldReturnOriginalWhenWithinLimit() {
        String text = "abc\ndef";
        assertThat(TextTruncator.truncateAtLineBoundary(text, text.length())).isSameAs(text);
        assertThat(TextTruncator.truncateAtLineBoundary(text, 100)).isSameAs(text);
    }

    @Test
    @DisplayName("null 或非法上限 → 原样返回")
    void shouldReturnOriginalForNullOrNonPositiveLimit() {
        assertThat(TextTruncator.truncateAtLineBoundary(null, 10)).isNull();
        String text = "abc\ndef";
        assertThat(TextTruncator.truncateAtLineBoundary(text, 0)).isSameAs(text);
        assertThat(TextTruncator.truncateAtLineBoundary(text, -5)).isSameAs(text);
    }

    @Test
    @DisplayName("回退窗口内有换行 → 截到最近换行前（完整行收尾）")
    void shouldBacktrackToLineBoundaryWithinWindow() {
        String text = "行".repeat(3500) + "\n" + "后".repeat(2000);
        String result = TextTruncator.truncateAtLineBoundary(text, 4000);
        assertThat(result).isEqualTo("行".repeat(3500));
        assertThat(result).doesNotContain("\n");
    }

    @Test
    @DisplayName("多个换行 → 取最近换行处截断（非第一个）")
    void shouldBacktrackToNearestNewline() {
        String text = "a".repeat(3900) + "\n" + "b".repeat(50) + "\n" + "c".repeat(1000);
        String result = TextTruncator.truncateAtLineBoundary(text, 4000);
        assertThat(result).isEqualTo("a".repeat(3900) + "\n" + "b".repeat(50));
    }

    @Test
    @DisplayName("回退窗口内无换行 → 按字符硬切（保底不抛）")
    void shouldHardCutWhenNoNewlineWithinWindow() {
        // 换行位于第 100 字符，远在回退窗口（maxChars-512 = 1488）之外
        String text = "a".repeat(100) + "\n" + "b".repeat(3000);
        String result = TextTruncator.truncateAtLineBoundary(text, 2000);
        assertThat(result).isEqualTo(text.substring(0, 2000));
        assertThat(result).hasSize(2000);
    }

    @Test
    @DisplayName("换行恰在回退窗口边界（floor）→ 仍回退到换行处")
    void shouldBacktrackWhenNewlineExactlyAtWindowEdge() {
        int maxChars = 1000;
        int floor = maxChars - TextTruncator.LINE_BOUNDARY_LOOKBACK;
        String text = "a".repeat(floor) + "\n" + "b".repeat(2000);
        assertThat(TextTruncator.truncateAtLineBoundary(text, maxChars))
                .isEqualTo("a".repeat(floor));
    }

    @Test
    @DisplayName("换行在回退窗口外一格 → 按字符硬切")
    void shouldHardCutWhenNewlineJustOutsideWindow() {
        int maxChars = 1000;
        int floor = maxChars - TextTruncator.LINE_BOUNDARY_LOOKBACK;
        String text = "a".repeat(floor - 1) + "\n" + "b".repeat(2000);
        assertThat(TextTruncator.truncateAtLineBoundary(text, maxChars))
                .isEqualTo(text.substring(0, maxChars));
    }
}
