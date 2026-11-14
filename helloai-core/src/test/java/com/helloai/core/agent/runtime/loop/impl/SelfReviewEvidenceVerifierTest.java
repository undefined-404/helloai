package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `SR-2` 自审证据对账：事实源是平台台账，模型自填只作对照。
 */
@DisplayName("SR-2 自审证据对账 SelfReviewEvidenceVerifier")
class SelfReviewEvidenceVerifierTest {

    @Test
    @DisplayName("无证据段 ⇒ MISSING（台账条数如实回填）")
    void missingWhenAbsent() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse("STATUS: DONE"), List.of(entry("pwd", 0, false, "")));

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISSING);
        assertThat(result.claimedCount()).isZero();
        assertThat(result.ledgerCount()).isEqualTo(1);
        assertThat(result.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("证据块畸形 / 空数组 ⇒ MALFORMED / MISSING")
    void malformedAndEmpty() {
        SelfReviewEvidenceVerifier.Result malformed = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse("EVIDENCE: not-json"), List.of());
        assertThat(malformed.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MALFORMED);

        SelfReviewEvidenceVerifier.Result empty = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse("EVIDENCE: []"), List.of());
        assertThat(empty.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISSING);
    }

    @Test
    @DisplayName("字段全一致 ⇒ VERIFIED")
    void verifiedWhenAllFieldsMatch() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence("cat draft.md", 0, false, "sha256:ABCDEF")),
                List.of(entry("cat draft.md", 0, false, "sha256:abcdef")));

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.claimedCount()).isEqualTo(1);
        assertThat(result.mismatches()).isEmpty();
    }

    @Test
    @DisplayName("digest 自报为空 ⇒ 降级为 command + exitCode + truncated 三项比对")
    void emptyDigestDegradesToThreeFields() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence("pwd", 0, false, "")),
                List.of(entry("pwd", 0, false, "sha256:whatever")));

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
    }

    @Test
    @DisplayName("★ 模型自报 exitCode=0 而台账为 1 ⇒ MISMATCH（self_reported_mismatch）")
    void exitCodeMismatchIsFlagged() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence("python check.py", 0, false, "")),
                List.of(entry("python check.py", 1, false, "")));

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
        assertThat(result.mismatches()).hasSize(1);
        assertThat(result.mismatches().get(0)).contains("exitCode=0").contains("无匹配记录");
    }

    @Test
    @DisplayName("台账命令为 200 字符截断预览 ⇒ 按前缀匹配")
    void matchesTruncatedLedgerCommand() {
        String full = "python - <<'PY' " + "print('x');".repeat(30);
        String preview = full.substring(0, 200) + "...";

        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence(full, 0, true, "")),
                List.of(entry(preview, 0, true, "")));

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
    }

    @Test
    @DisplayName("一条台账记录不得被两条自填证据重复消费")
    void doesNotReuseLedgerEntry() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse("EVIDENCE: ["
                        + item("pwd", 0, false, "")
                        + "," + item("pwd", 0, false, "") + "]"),
                List.of(entry("pwd", 0, false, "")));

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
        assertThat(result.claimedCount()).isEqualTo(2);
        assertThat(result.mismatches()).hasSize(1);
    }

    @Test
    @DisplayName("不一致明细最多保留 MAX_MISMATCH_DETAILS 条（事件 payload 有界）")
    void mismatchDetailsAreCapped() {
        StringBuilder block = new StringBuilder("EVIDENCE: [");
        for (int i = 0; i < SelfReviewEvidenceVerifier.MAX_MISMATCH_DETAILS + 3; i++) {
            if (i > 0) {
                block.append(',');
            }
            block.append(item("cmd-" + i, 0, false, ""));
        }
        block.append(']');

        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(block.toString()), List.of());

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
        assertThat(result.mismatches()).hasSize(SelfReviewEvidenceVerifier.MAX_MISMATCH_DETAILS);
    }

    // ==============================================================
    // `SL-5`：引用式（callId）+ 来源 / 轮次过滤
    // ==============================================================

    @Test
    @DisplayName("SL-5 引用 callId 命中 ⇒ VERIFIED，并回填被证实的条目")
    void referenceByCallIdVerifies() {
        SandboxCallLedger.Entry hit = entry("c7", "pwd", 0, false, "", "executor", 2);
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(reference("c7")), List.of(hit), 2);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.matched()).containsExactly(hit);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    @DisplayName("SL-5 引用不存在的 callId ⇒ MISMATCH")
    void referenceUnknownCallIdIsMismatch() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(reference("c99")),
                List.of(entry("c7", "pwd", 0, false, "", "executor", 1)), 1);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
        assertThat(result.mismatches()).anyMatch(reason -> reason.contains("c99"));
        assertThat(result.matched()).isEmpty();
    }

    @Test
    @DisplayName("SL-5 引用真实条目但自报结果与台账矛盾 ⇒ MISMATCH（堵「引用 + 编造」）")
    void referenceContradictingFieldsIsMismatch() {
        String fence = "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"c7\",\"exitCode\":0}]";
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(fence),
                List.of(entry("c7", "pwd", 1, false, "", "executor", 1)), 1);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
        assertThat(result.mismatches()).anyMatch(reason -> reason.contains("矛盾"));
    }

    @Test
    @DisplayName("SL-5 引用 reviewer 条目 ⇒ 仍 VERIFIED，但记 evidence_origin_non_author")
    void referenceReviewerEntryAddsOriginNote() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(reference("c8")),
                List.of(entry("c8", "pytest", 0, false, "", "reviewer", 1)), 1);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.notes()).anyMatch(note -> note.contains("evidence_origin_non_author"));
    }

    @Test
    @DisplayName("SL-5 引用跨轮条目 ⇒ 仍 VERIFIED，但记 evidence_round_crossed")
    void referenceCrossRoundAddsNote() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(reference("c8")),
                List.of(entry("c8", "pytest", 0, false, "", "executor", 1)), 3);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.notes()).anyMatch(note -> note.contains("evidence_round_crossed"));
    }

    @Test
    @DisplayName("SL-5 旧格式只认作者条目：reviewer 同名命令不能顶替作者证据")
    void legacyIgnoresReviewerEntries() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence("pytest", 0, false, "")),
                List.of(entry("c9", "pytest", 0, false, "", "reviewer", 1)), 1);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
    }

    @Test
    @DisplayName("SL-5 旧格式优先本轮：同命令跨轮各一条 ⇒ 命中本轮且无跨轮告警")
    void legacyPrefersCurrentRound() {
        SandboxCallLedger.Entry previous = entry("c1", "pwd", 0, false, "", "executor", 1);
        SandboxCallLedger.Entry current = entry("c2", "pwd", 0, false, "", "executor", 2);
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence("pwd", 0, false, "")),
                List.of(previous, current), 2);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.matched()).containsExactly(current);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    @DisplayName("SL-5 旧格式本轮无命中才回退全账本，并记 evidence_round_crossed")
    void legacyFallsBackAndNotesCrossRound() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse(evidence("pwd", 0, false, "")),
                List.of(entry("c1", "pwd", 0, false, "", "executor", 1)), 2);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.VERIFIED);
        assertThat(result.notes()).anyMatch(note -> note.contains("evidence_round_crossed"));
    }

    @Test
    @DisplayName("SL-5 同一条目不得被两条引用重复消费")
    void referenceDoesNotReuseEntry() {
        SelfReviewEvidenceVerifier.Result result = SelfReviewEvidenceVerifier.verify(
                SelfReviewEvidenceParser.parse("EVIDENCE: ["
                        + "{\"kind\":\"sandbox_exec\",\"callId\":\"c7\"},"
                        + "{\"kind\":\"sandbox_exec\",\"callId\":\"c7\"}]"),
                List.of(entry("c7", "pwd", 0, false, "", "executor", 1)), 1);

        assertThat(result.outcome()).isEqualTo(SelfReviewEvidenceVerifier.Outcome.MISMATCH);
        assertThat(result.matched()).hasSize(1);
        assertThat(result.mismatches()).anyMatch(reason -> reason.contains("已被其它证据占用"));
    }

    private static String reference(String callId) {
        return "EVIDENCE: [{\"kind\":\"sandbox_exec\",\"callId\":\"" + callId + "\"}]";
    }

    private static SandboxCallLedger.Entry entry(String callId, String command, int exitCode,
                                                 boolean truncated, String digest, String origin, int round) {
        return new SandboxCallLedger.Entry(command, exitCode, truncated, digest, OffsetDateTime.now(),
                callId, origin, round);
    }

    private static String evidence(String command, int exitCode, boolean truncated, String digest) {
        return "EVIDENCE: [" + item(command, exitCode, truncated, digest) + "]";
    }

    private static String item(String command, int exitCode, boolean truncated, String digest) {
        return "{\"kind\":\"sandbox_exec\",\"command\":\"" + command + "\",\"exitCode\":" + exitCode
                + ",\"truncated\":" + truncated + ",\"digest\":\"" + digest + "\"}";
    }

    private static SandboxCallLedger.Entry entry(String command, int exitCode, boolean truncated, String digest) {
        return new SandboxCallLedger.Entry(command, exitCode, truncated, digest, OffsetDateTime.now(),
                "c1", "executor", 1);
    }
}
