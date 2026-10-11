package com.helloai.core.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KbCharBudget —— REF-4.1 char_budget 注入预算")
class KbCharBudgetTest {

    @Test
    @DisplayName("未超预算：原文透传")
    void withinBudget_passthrough() {
        assertThat(KbCharBudget.truncate("abc", 10)).isEqualTo("abc");
    }

    @Test
    @DisplayName("null 原样返回")
    void nullContent() {
        assertThat(KbCharBudget.truncate(null, 10)).isNull();
    }

    @Test
    @DisplayName("恰好等于预算：不截断")
    void equalBudget_notTruncated() {
        assertThat(KbCharBudget.truncate("12345", 5)).isEqualTo("12345");
    }

    @Test
    @DisplayName("超预算：结果长度 ≤ 预算")
    void overBudget_capped() {
        String text = "第1行\n" + "第2行内容比较长\n" + "第3行\n" + "第4行\n" + "第5行\n";
        String out = KbCharBudget.truncate(text, 8);
        assertThat(out.length()).isLessThanOrEqualTo(8);
    }
}