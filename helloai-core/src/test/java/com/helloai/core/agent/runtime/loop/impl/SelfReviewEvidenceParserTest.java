package com.helloai.core.agent.runtime.loop.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `SR-2` 自审证据块解析器：非法输入一律判「缺失 / 畸形」且永不抛异常。
 */
@DisplayName("SR-2 自审证据解析 SelfReviewEvidenceParser")
class SelfReviewEvidenceParserTest {

    @Test
    @DisplayName("无 EVIDENCE 段 / 空正文 ⇒ ABSENT（不抛）")
    void absentWhenNoMarker() {
        assertThat(SelfReviewEvidenceParser.parse(null).status())
                .isEqualTo(SelfReviewEvidenceParser.Status.ABSENT);
        assertThat(SelfReviewEvidenceParser.parse("   ").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.ABSENT);
        assertThat(SelfReviewEvidenceParser.parse("VERIFICATION:\n- 已核对\nSTATUS: DONE").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.ABSENT);
    }

    @Test
    @DisplayName("非法 JSON / 非数组 / 元素非对象 / 缺键 / 类型不符 ⇒ MALFORMED（不抛）")
    void malformedOnContractViolation() {
        assertThat(SelfReviewEvidenceParser.parse("EVIDENCE: not-json").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        assertThat(SelfReviewEvidenceParser.parse("EVIDENCE: {\"kind\":\"sandbox_exec\"}").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        assertThat(SelfReviewEvidenceParser.parse("EVIDENCE: [\"sandbox_exec\"]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        // 缺 digest 键（digest 值可为空串，但键必须存在）
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"ls\",\"exitCode\":0,"
                        + "\"truncated\":false}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        // exitCode 非整数
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"ls\",\"exitCode\":0.5,"
                        + "\"truncated\":false,\"digest\":\"\"}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        // truncated 非布尔
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"ls\",\"exitCode\":0,"
                        + "\"truncated\":\"false\",\"digest\":\"\"}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
    }

    @Test
    @DisplayName("合法证据块 ⇒ PARSED 并逐字段解析")
    void parsesValidEvidence() {
        SelfReviewEvidenceParser.Parsed parsed = SelfReviewEvidenceParser.parse(
                "正文\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无\n"
                        + "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"cat /workspace/draft.md\","
                        + "\"exitCode\":0,\"truncated\":false,\"digest\":\"sha256:abc123\"}]");

        assertThat(parsed.status()).isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        assertThat(parsed.usable()).isTrue();
        assertThat(parsed.entries()).hasSize(1);
        SelfReviewEvidenceParser.Evidence evidence = parsed.entries().get(0);
        assertThat(evidence.kind()).isEqualTo("sandbox_exec");
        assertThat(evidence.command()).isEqualTo("cat /workspace/draft.md");
        assertThat(evidence.exitCode()).isZero();
        assertThat(evidence.truncated()).isFalse();
        assertThat(evidence.digest()).isEqualTo("sha256:abc123");
    }

    @Test
    @DisplayName("SL-5 引用式：只给 callId ⇒ PARSED，旧字段为空")
    void parsesReferenceByCallId() {
        SelfReviewEvidenceParser.Parsed parsed = SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"c17\"}]");

        assertThat(parsed.status()).isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        SelfReviewEvidenceParser.Evidence evidence = parsed.entries().get(0);
        assertThat(evidence.hasCallId()).isTrue();
        assertThat(evidence.callId()).isEqualTo("c17");
        assertThat(evidence.command()).isNull();
        assertThat(evidence.exitCode()).isNull();
        assertThat(evidence.truncated()).isNull();
        assertThat(evidence.digest()).isNull();
    }

    @Test
    @DisplayName("SL-5 引用式：旧字段可选，但出现即须类型合法（写坏 ⇒ MALFORMED）")
    void referenceOptionalFieldsMustBeTyped() {
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"c17\","
                        + "\"command\":\"pwd\",\"exitCode\":0,\"truncated\":false}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"c17\",\"exitCode\":\"0\"}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"c17\",\"truncated\":\"no\"}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
        // 空白 callId 视同未给 ⇒ 回落旧格式，而旧格式缺 command ⇒ MALFORMED
        assertThat(SelfReviewEvidenceParser.parse(
                "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"  \"}]").status())
                .isEqualTo(SelfReviewEvidenceParser.Status.MALFORMED);
    }

    @Test
    @DisplayName("空数组 ⇒ PARSED 但不可用（未执行验证的合法声明）")
    void emptyArrayIsParsedButNotUsable() {
        SelfReviewEvidenceParser.Parsed parsed = SelfReviewEvidenceParser.parse("EVIDENCE: []");

        assertThat(parsed.status()).isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        assertThat(parsed.entries()).isEmpty();
        assertThat(parsed.usable()).isFalse();
    }

    @Test
    @DisplayName("容忍 ```json 代码块包裹")
    void toleratesCodeFence() {
        SelfReviewEvidenceParser.Parsed parsed = SelfReviewEvidenceParser.parse("""
                EVIDENCE:
                ```json
                [{"kind":"sandbox_exec","command":"pwd","exitCode":0,"truncated":false,"digest":""}]
                ```""");

        assertThat(parsed.status()).isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        assertThat(parsed.entries()).hasSize(1);
        assertThat(parsed.entries().get(0).command()).isEqualTo("pwd");
    }

    @Test
    @DisplayName("取最后一个 EVIDENCE 标记（重试 / 草稿残留不干扰）")
    void usesLastMarker() {
        SelfReviewEvidenceParser.Parsed parsed = SelfReviewEvidenceParser.parse(
                "EVIDENCE: not-json\n"
                        + "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"pwd\",\"exitCode\":0,"
                        + "\"truncated\":false,\"digest\":\"\"}]");

        assertThat(parsed.status()).isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        assertThat(parsed.entries()).hasSize(1);
    }
}
