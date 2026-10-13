package com.helloai.core.shared.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** VerificationFence 围栏解析器单测（REF-8.4 ③）。 */
@DisplayName("VerificationFence")
class VerificationFenceTest {

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
}