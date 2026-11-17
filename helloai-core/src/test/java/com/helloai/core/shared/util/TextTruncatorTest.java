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

    // ────────────────────────────────────────────────────────────────
    // 头尾双向保留（A-2 写侧 64k 预算；保 EXECUTION_RECORD 尾部块）
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("headTail：未超限 / null / 非法上限 → 原样返回")
    void headTailShouldReturnOriginalWhenWithinLimit() {
        String text = "abc\ndef";
        assertThat(TextTruncator.truncateHeadTail(text, text.length())).isSameAs(text);
        assertThat(TextTruncator.truncateHeadTail(null, 10)).isNull();
        assertThat(TextTruncator.truncateHeadTail(text, 0)).isSameAs(text);
    }

    @Test
    @DisplayName("headTail：尾部 EXECUTION_RECORD 块必须保留（只保头会把它切掉）")
    void headTailShouldKeepExecutionRecordTail() {
        int maxChars = 1000;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("中间正文第").append(i).append("行，用来把总长度顶到上限之上\n");
        }
        sb.append("EXECUTION_RECORD:\n{\"files\":[\"a.md\"]}");
        String text = sb.toString();
        assertThat(text.length()).isGreaterThan(maxChars);

        String result = TextTruncator.truncateHeadTail(text, maxChars);

        assertThat(result).contains("EXECUTION_RECORD:");
        assertThat(result).contains("{\"files\":[\"a.md\"]}");
        assertThat(result).contains("中间省略");
        assertThat(result.length()).isLessThanOrEqualTo(maxChars);
        // 头尾都没被截成半个字符序列：结果以原文头部起、以原文尾部止
        assertThat(result).startsWith("中间正文第0行");
        assertThat(result).endsWith("{\"files\":[\"a.md\"]}");
    }

    @Test
    @DisplayName("headTail：省略字符数如实反映被丢掉的长度（不误导消费方）")
    void headTailShouldReportOmittedLength() {
        int maxChars = 600;
        String text = "H".repeat(400) + "\n" + "M".repeat(2000) + "\n" + "T".repeat(200);
        String result = TextTruncator.truncateHeadTail(text, maxChars);

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?s)^(.*)\n…（中间省略 (\\d+) 字符）…\n(.*)$")
                .matcher(result);
        assertThat(matcher.matches()).isTrue();
        int omitted = Integer.parseInt(matcher.group(2));
        int kept = matcher.group(1).length() + matcher.group(3).length();
        assertThat(omitted).isEqualTo(text.length() - kept);
        assertThat(omitted).isPositive();
    }

    @Test
    @DisplayName("headTail：上限过小（容不下标记）→ 退回行边界硬切，不抛且不超长")
    void headTailShouldFallBackWhenLimitTooSmall() {
        String text = "a".repeat(500);
        String result = TextTruncator.truncateHeadTail(text, 10);
        assertThat(result).hasSize(10);
        assertThat(result).doesNotContain("中间省略");
    }
}
