package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** VerificationFence 围栏解析器单测（REF-8.4 ③）。 */
@DisplayName("VerificationFence")
class VerificationFenceTest {

    @Test
    @DisplayName("bodyWithoutFence：剥掉尾段围栏得正文；只含围栏 → 正文为空（整合报告 fail-close 判据）")
    void bodyWithoutFence() {
        assertThat(VerificationFence.bodyWithoutFence(null)).isEmpty();
        // 正常交付物：正文 + 尾段围栏 ⇒ 正文完整保留
        assertThat(VerificationFence.bodyWithoutFence(
                "# 报告\n\n## 执行摘要\n正文\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无"))
                .contains("## 执行摘要");
        // 事故形态（2026-10-16）：首字符即围栏 ⇒ 正文为空 ⇒ 调用方 fail-close
        assertThat(VerificationFence.bodyWithoutFence(
                "VERIFICATION:\n- 对照六大目标核实…\nSTATUS: DONE\nNEXT_STEPS: 无").isBlank()).isTrue();
        // 无围栏 ⇒ 原样返回（判据不误伤普通产出）
        assertThat(VerificationFence.bodyWithoutFence("只有正文，无围栏")).isEqualTo("只有正文，无围栏");
    }

    @Test
    @DisplayName("containsMarker：null/空 → false；含 VERIFICATION: → true；不含 → false")
    void containsMarker() {
        assertThat(VerificationFence.containsMarker(null)).isFalse();
        assertThat(VerificationFence.containsMarker("")).isFalse();
        assertThat(VerificationFence.containsMarker("summary")).isFalse();
        assertThat(VerificationFence.containsMarker("前文\nVERIFICATION: 已核对\n")).isTrue();
    }

    @Test
    @DisplayName("parse：完整围栏（VERIFICATION + STATUS:DONE + NEXT_STEPS）→ 合格")
    void parseAdequate() {
        VerificationFence.Parsed parsed = VerificationFence.parse(
                "完成\nVERIFICATION:\n- 输出示例\nSTATUS: DONE\nNEXT_STEPS: 无");
        assertThat(parsed.present()).isTrue();
        assertThat(parsed.status()).isEqualTo("DONE");
        assertThat(parsed.hasNextSteps()).isTrue();
        assertThat(parsed.adequate()).isTrue();
    }

    @Test
    @DisplayName("parse：有 VERIFICATION 但无 STATUS → 不合规（status null，adequate false）")
    void parseWithoutStatus() {
        VerificationFence.Parsed parsed = VerificationFence.parse("VERIFICATION: 无 STATUS");
        assertThat(parsed.present()).isTrue();
        assertThat(parsed.status()).isNull();
        assertThat(parsed.adequate()).isFalse();
    }

    @Test
    @DisplayName("parse：缺 VERIFICATION → present false（即使有 NEXT_STEPS）")
    void parseMissingMarker() {
        VerificationFence.Parsed parsed = VerificationFence.parse("STATUS: PARTIAL\nNEXT_STEPS: 需重试");
        assertThat(parsed.present()).isFalse();
        assertThat(parsed.adequate()).isFalse();
    }

    @Test
    @DisplayName("parse：null/空白 → 空结果（present false、status null、no nextSteps）")
    void parseBlank() {
        VerificationFence.Parsed parsed = VerificationFence.parse("   ");
        assertThat(parsed.present()).isFalse();
        assertThat(parsed.status()).isNull();
        assertThat(parsed.hasNextSteps()).isFalse();
        assertThat(parsed.adequate()).isFalse();
    }

    @Test
    @DisplayName("parse：STATUS 带 Markdown 前置装饰（**/-/>）也能解析出值（对齐真实模型输出）")
    void parseStatusWithMarkdownDecorators() {
        assertThat(VerificationFence.parse("**STATUS: DONE**\n").status()).isEqualTo("DONE");
        assertThat(VerificationFence.parse("- STATUS: BLOCKED\n").status()).isEqualTo("BLOCKED");
        assertThat(VerificationFence.parse("> STATUS: PARTIAL\n").status()).isEqualTo("PARTIAL");
        assertThat(VerificationFence.parse("STATUS:DONE\n").status()).isEqualTo("DONE");   // 纯 STATUS:DONE（无空格冒号）
        assertThat(VerificationFence.parse("STATUS：完成\n").status()).isEqualTo("完成");   // 全角冒号 + 中文值
    }

    @Test
    @DisplayName("parse：STATUS 混写（列表符 + 粗体 + 有序）也能解析出值（堵假绿）")
    void parseStatusWithMixedDecorators() {
        assertThat(VerificationFence.parse("- **STATUS: DONE**\n").status()).isEqualTo("DONE");       // 无序 + 粗体
        assertThat(VerificationFence.parse("1. **STATUS: DONE**\n").status()).isEqualTo("DONE");       // 有序 + 粗体
        assertThat(VerificationFence.parse("   - **STATUS: BLOCKED**\n").status()).isEqualTo("BLOCKED"); // 缩进 + 无序 + 粗体
        assertThat(VerificationFence.parse("> **STATUS: PARTIAL**\n").status()).isEqualTo("PARTIAL");   // 引用 + 粗体
        assertThat(VerificationFence.parse("5. STATUS：完成\n").status()).isEqualTo("完成");            // 有序 + 中文值
    }

    @Test
    @DisplayName("stripTrailingFence：五态 —— 无围栏原样／合格尾围栏剥离／正文含 VERIFICATION: 字样不误截／纯围栏原样／空值原样")
    void stripTrailingFence() {
        // ① 无围栏 ⇒ 原样返回（不误伤普通产出）
        assertThat(VerificationFence.stripTrailingFence("只有正文，无围栏"))
                .isEqualTo("只有正文，无围栏");
        // ② 合格尾围栏（VERIFICATION + STATUS） ⇒ 剥掉整段，保留正文
        assertThat(VerificationFence.stripTrailingFence(
                "# 报告\n正文段落\n\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无"))
                .isEqualTo("# 报告\n正文段落");
        // ③ 正文里出现 VERIFICATION: 字样但尾段非合格围栏 ⇒ 原样返回（章节讲解围栏协议不误截）
        assertThat(VerificationFence.stripTrailingFence(
                "## 围栏协议\n本节讲 VERIFICATION: 的写法，并举例"))
                .isEqualTo("## 围栏协议\n本节讲 VERIFICATION: 的写法，并举例");
        // ④ 纯围栏（无正文） ⇒ 原样返回，交完整性判据 fail-close（本方法不吞掉证据）
        String fenceOnly = "VERIFICATION:\n- 核实\nSTATUS: DONE";
        assertThat(VerificationFence.stripTrailingFence(fenceOnly)).isEqualTo(fenceOnly);
        // ⑤ null / 空白 ⇒ 原样返回
        assertThat(VerificationFence.stripTrailingFence(null)).isNull();
        assertThat(VerificationFence.stripTrailingFence("   ")).isEqualTo("   ");
    }
}
