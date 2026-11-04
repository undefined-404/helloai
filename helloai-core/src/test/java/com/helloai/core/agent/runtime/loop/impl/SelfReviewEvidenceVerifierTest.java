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

    private static String evidence(String command, int exitCode, boolean truncated, String digest) {
        return "EVIDENCE: [" + item(command, exitCode, truncated, digest) + "]";
    }

    private static String item(String command, int exitCode, boolean truncated, String digest) {
        return "{\"kind\":\"sandbox_exec\",\"command\":\"" + command + "\",\"exitCode\":" + exitCode
                + ",\"truncated\":" + truncated + ",\"digest\":\"" + digest + "\"}";
    }

    private static SandboxCallLedger.Entry entry(String command, int exitCode, boolean truncated, String digest) {
        return new SandboxCallLedger.Entry(command, exitCode, truncated, digest, OffsetDateTime.now());
    }
}
