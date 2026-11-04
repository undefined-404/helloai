package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SelfReviewGate} 单测（`SR-3` 判据 + `SR-4` 跳过档）。
 *
 * <p>只测「证据在不在、与台账对不对得上」，不测内容好坏——后者归平台 reviewer。</p>
 */
@DisplayName("SR-3 自审闸门 SelfReviewGate")
class SelfReviewGateTest {

    /** 文档类任务要求（不含可执行交付物关键词）。 */
    private static final String DOC_TASK = "请输出竞品分析的调研报告";

    /** 可执行交付物形态的任务要求。 */
    private static final String SCRIPT_TASK = "写一个批量重命名的脚本并交付";

    // ==============================================================
    // 跳过档：不判、恒通过
    // ==============================================================

    @Test
    @DisplayName("沙箱未启用 ⇒ SKIPPED_NO_SANDBOX 且通过（降级不拦截，SR-4）")
    void sandboxDisabled_skips() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(false, true,
                parsed("EVIDENCE: not-json"), null, List.of(), DOC_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SKIPPED_NO_SANDBOX);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("无子任务上下文 ⇒ SKIPPED_NO_SUB_TASK 且通过（读不到台账就不判，不猜）")
    void noSubTask_skips() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, false,
                SelfReviewEvidenceParser.Parsed.absent(), null, List.of(), SCRIPT_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SKIPPED_NO_SUB_TASK);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("开关关闭 ⇒ SKIPPED_DISABLED 且通过（回滚面：不重跑、不记失败）")
    void disabled_alwaysPasses() {
        SelfReviewGate.Result result = SelfReviewGate.disabled();

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SKIPPED_DISABLED);
        assertThat(result.passed()).isTrue();
        assertThat(result.summary()).isEmpty();
    }

    // ==============================================================
    // 档 1（SANDBOX_REQUIRED）：必须有一手证据
    // ==============================================================

    @Test
    @DisplayName("交付物声明为脚本 + 无证据 ⇒ 不通过，同时点出台账无成功记录")
    void scriptDeliverable_withoutEvidence_fails() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true,
                SelfReviewEvidenceParser.Parsed.absent(), null, List.of(), SCRIPT_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SANDBOX_REQUIRED);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).hasSize(2)
                .anyMatch(reason -> reason.contains("未提供任何 sandbox_exec 证据"))
                .anyMatch(reason -> reason.contains("exitCode=0 且未截断"));
    }

    @Test
    @DisplayName("自报了证据但台账无对应执行 ⇒ 不通过（声明必须兑现，不能只留观测）")
    void claimedEvidence_mismatch_fails() {
        String fence = "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"python check.py\","
                + "\"exitCode\":0,\"truncated\":false,\"digest\":\"\"}]";
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true, parsed(fence),
                SelfReviewEvidenceVerifier.verify(parsed(fence), List.of(entry("pwd", 0, false))),
                List.of(entry("pwd", 0, false)), DOC_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SANDBOX_REQUIRED);
        assertThat(result.passed()).isFalse();
        assertThat(result.summary()).contains("self_reported_mismatch").contains("python check.py");
    }

    @Test
    @DisplayName("台账只有失败执行（exitCode=1）⇒ 不算一手成功证据")
    void ledgerOnlyFailedExecution_fails() {
        String fence = "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"pytest\",\"exitCode\":1,"
                + "\"truncated\":false,\"digest\":\"\"}]";
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true, parsed(fence),
                SelfReviewEvidenceVerifier.verify(parsed(fence), List.of(entry("pytest", 1, false))),
                List.of(entry("pytest", 1, false)), DOC_TASK);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(reason -> reason.contains("exitCode=0 且未截断"));
    }

    @Test
    @DisplayName("台账只有截断输出（truncated=true）⇒ 同样不算成功证据")
    void ledgerOnlyTruncatedExecution_fails() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true,
                SelfReviewEvidenceParser.Parsed.absent(), null,
                List.of(entry("cat big.log", 0, true)), SCRIPT_TASK);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(reason -> reason.contains("exitCode=0 且未截断"));
    }

    @Test
    @DisplayName("自报证据与台账一致且台账有成功记录 ⇒ 通过")
    void verifiedEvidence_withSuccess_passes() {
        String fence = "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"command\":\"pwd\",\"exitCode\":0,"
                + "\"truncated\":false,\"digest\":\"\"}]";
        List<SandboxCallLedger.Entry> ledger = List.of(entry("pwd", 0, false));
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true, parsed(fence),
                SelfReviewEvidenceVerifier.verify(parsed(fence), ledger), ledger, DOC_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SANDBOX_REQUIRED);
        assertThat(result.passed()).isTrue();
    }

    // ==============================================================
    // 档 2（EVIDENCE_FORM_ONLY）：只校验形态
    // ==============================================================

    @Test
    @DisplayName("文档类 + 无证据块 ⇒ 通过（否则文档类任务会被系统性拦截）")
    void documentDeliverable_withoutEvidence_passes() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true,
                SelfReviewEvidenceParser.Parsed.absent(), null, List.of(), DOC_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.EVIDENCE_FORM_ONLY);
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("文档类 + 证据块不合契约 ⇒ 不通过（两档都拦：非法 JSON 无法核对）")
    void documentDeliverable_malformed_fails() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true,
                parsed("EVIDENCE: 我保证都验证过了"), null, List.of(), DOC_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.EVIDENCE_FORM_ONLY);
        assertThat(result.passed()).isFalse();
        assertThat(result.summary()).contains("不合契约");
    }

    @Test
    @DisplayName("脚本类 + 证据块不合契约 ⇒ 档 1 且不通过（提前返回，不叠加其它判据）")
    void scriptDeliverable_malformed_failsAsTierOne() {
        SelfReviewGate.Result result = SelfReviewGate.evaluate(true, true,
                parsed("EVIDENCE: {不是数组}"), null, List.of(), SCRIPT_TASK);

        assertThat(result.tier()).isEqualTo(SelfReviewGate.Tier.SANDBOX_REQUIRED);
        assertThat(result.failures()).hasSize(1);
    }

    // ==============================================================
    // 判据细节
    // ==============================================================

    @Test
    @DisplayName("关键词只认交付物形态：「执行摘要 / 已执行」不升档 1")
    void verbsInRequirementDoNotTriggerTierOne() {
        assertThat(SelfReviewGate.requiresSandboxEvidence("请给出执行摘要，说明已执行的调研步骤",
                SelfReviewEvidenceParser.Parsed.absent())).isFalse();
        assertThat(SelfReviewGate.requiresSandboxEvidence(SCRIPT_TASK,
                SelfReviewEvidenceParser.Parsed.absent())).isTrue();
        assertThat(SelfReviewGate.requiresSandboxEvidence(null,
                SelfReviewEvidenceParser.Parsed.absent())).isFalse();
    }

    @Test
    @DisplayName("空证据数组不算「自报」⇒ 不升档 1")
    void emptyEvidenceArrayDoesNotTriggerTierOne() {
        SelfReviewEvidenceParser.Parsed parsed = parsed("EVIDENCE: []");

        assertThat(parsed.status()).isEqualTo(SelfReviewEvidenceParser.Status.PARSED);
        assertThat(SelfReviewGate.requiresSandboxEvidence(DOC_TASK, parsed)).isFalse();
    }

    @Test
    @DisplayName("台账成功性判据：null / 空 / 含一条成功记录")
    void successfulExecutionDetection() {
        assertThat(SelfReviewGate.hasSuccessfulExecution(null)).isFalse();
        assertThat(SelfReviewGate.hasSuccessfulExecution(List.of())).isFalse();
        assertThat(SelfReviewGate.hasSuccessfulExecution(
                List.of(entry("a", 1, false), entry("b", 0, true)))).isFalse();
        assertThat(SelfReviewGate.hasSuccessfulExecution(
                List.of(entry("a", 1, false), entry("b", 0, false)))).isTrue();
    }

    @Test
    @DisplayName("摘要把多条原因拼接并截断到 500 字符")
    void summaryJoinsAndTruncates() {
        SelfReviewGate.Result result = new SelfReviewGate.Result(
                SelfReviewGate.Tier.SANDBOX_REQUIRED,
                List.of("原因一", "x".repeat(600)));

        assertThat(result.summary()).startsWith("原因一；").endsWith("...").hasSize(503);
        assertThat(new SelfReviewGate.Result(SelfReviewGate.Tier.SANDBOX_REQUIRED, null).passed()).isTrue();
    }

    private static SelfReviewEvidenceParser.Parsed parsed(String fence) {
        return SelfReviewEvidenceParser.parse("正文\n" + fence);
    }

    private static SandboxCallLedger.Entry entry(String command, int exitCode, boolean truncated) {
        return new SandboxCallLedger.Entry(command, exitCode, truncated, "", OffsetDateTime.now());
    }

    @Test
    @DisplayName("交付物声明只取「交付物要求:」那一行，不把平台提示正文里的「脚本」算进形态判定")
    void deliverableDeclarationTakesSingleLineOnly() {
        String prompt = "平台提示：如需验证可运行脚本、写沙箱文件\n"
                + "交付物要求: 最终说明正文（≤120 汉字）\n"
                + "STATUS 按契约填写";

        assertThat(SelfReviewGate.deliverableDeclaration(prompt))
                .isEqualTo("交付物要求: 最终说明正文（≤120 汉字）");
        // 用声明行判形态 ⇒ 文档类落档 2，而非被提示词正文的「脚本」误判成档 1
        assertThat(SelfReviewGate.evaluate(true, true, parsed("VERIFICATION:\n- 已验证\nSTATUS: DONE\nNEXT_STEPS: 无"),
                SelfReviewEvidenceVerifier.verify(
                        parsed("VERIFICATION:\n- 已验证\nSTATUS: DONE\nNEXT_STEPS: 无"), List.of()),
                List.of(), SelfReviewGate.deliverableDeclaration(prompt)).tier())
                .isEqualTo(SelfReviewGate.Tier.EVIDENCE_FORM_ONLY);
    }

    @Test
    @DisplayName("无「交付物要求:」声明行 / 空输入 ⇒ 返回空串（按档 2 处理，宁可少拦）")
    void deliverableDeclarationEmptyWhenMissing() {
        assertThat(SelfReviewGate.deliverableDeclaration(null)).isEmpty();
        assertThat(SelfReviewGate.deliverableDeclaration("")).isEmpty();
        assertThat(SelfReviewGate.deliverableDeclaration("请编写脚本并运行验证")).isEmpty();
    }
}
