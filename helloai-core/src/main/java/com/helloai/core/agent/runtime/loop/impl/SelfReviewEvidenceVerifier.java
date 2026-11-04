package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自审证据对账（`SR-2`，纯静态）。
 *
 * <p><b>事实源</b>：以 {@link SandboxCallLedger} 的平台侧记录为准，模型自填的
 * {@code EVIDENCE} 块只作对照。自填条目在台账里找不到对应执行事实（command / exitCode /
 * truncated 不一致，或 digest 不一致）⇒ {@link Outcome#MISMATCH}（{@code self_reported_mismatch}）。</p>
 *
 * <p><b>匹配规则</b>：一条台账记录最多匹配一条自填证据（贪心、不重复消费）；命令按前缀方向匹配
 * （台账只存 200 字符预览，自填命令可能更短或更长）；digest 为空串表示模型未计算——
 * 此时降级为三项比对（command + exitCode + truncated），不判不一致。</p>
 *
 * <p>本类<b>不判</b>「证据是否足以证明交付物正确」——那是 `SR-3` 闸门的职责。</p>
 */
public final class SelfReviewEvidenceVerifier {

    /** 对账结论。 */
    public enum Outcome {
        /** 无证据块 / 证据块为空数组。 */
        MISSING,
        /** 证据块不合契约（见 {@link SelfReviewEvidenceParser.Status#MALFORMED}）。 */
        MALFORMED,
        /** 每条自填证据都能在台账找到对应执行事实。 */
        VERIFIED,
        /** 至少一条自填证据与台账不一致（self_reported_mismatch）。 */
        MISMATCH
    }

    /**
     * 对账结果。
     *
     * @param outcome      结论
     * @param claimedCount 自填证据条数
     * @param ledgerCount  台账条数
     * @param mismatches   不一致说明（最多 {@value #MAX_MISMATCH_DETAILS} 条，可直接进事件 payload）
     */
    public record Result(Outcome outcome, int claimedCount, int ledgerCount, List<String> mismatches) {

        public Result {
            mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
        }
    }

    /** 事件 payload 里最多列出的不一致条数。 */
    public static final int MAX_MISMATCH_DETAILS = 5;

    /** 台账命令预览上限（与 {@link SandboxCallLedger#commandPreview} 同口径）。 */
    private static final int COMMAND_KEY_CHARS = 200;

    private SelfReviewEvidenceVerifier() {
    }

    /**
     * 对账。
     *
     * @param parsed 解析结果（非 null）
     * @param ledger 该子任务的台账记录（按时间升序；可空）
     */
    public static Result verify(SelfReviewEvidenceParser.Parsed parsed, List<SandboxCallLedger.Entry> ledger) {
        List<SandboxCallLedger.Entry> entries = ledger == null ? List.of() : ledger;
        if (parsed == null || parsed.status() == SelfReviewEvidenceParser.Status.ABSENT) {
            return new Result(Outcome.MISSING, 0, entries.size(), List.of());
        }
        if (parsed.status() == SelfReviewEvidenceParser.Status.MALFORMED) {
            return new Result(Outcome.MALFORMED, 0, entries.size(), List.of());
        }
        List<SelfReviewEvidenceParser.Evidence> claimed = parsed.entries();
        if (claimed.isEmpty()) {
            return new Result(Outcome.MISSING, 0, entries.size(), List.of());
        }
        Set<Integer> used = new HashSet<>();
        List<String> mismatches = new ArrayList<>();
        for (SelfReviewEvidenceParser.Evidence evidence : claimed) {
            int matched = findMatch(evidence, entries, used);
            if (matched >= 0) {
                used.add(matched);
                continue;
            }
            if (mismatches.size() < MAX_MISMATCH_DETAILS) {
                mismatches.add("自报 command=" + commandKey(evidence.command())
                        + " exitCode=" + evidence.exitCode() + " truncated=" + evidence.truncated()
                        + " 在台账中无匹配记录");
            }
        }
        Outcome outcome = mismatches.isEmpty() ? Outcome.VERIFIED : Outcome.MISMATCH;
        return new Result(outcome, claimed.size(), entries.size(), mismatches);
    }

    private static int findMatch(SelfReviewEvidenceParser.Evidence evidence,
                                 List<SandboxCallLedger.Entry> ledger, Set<Integer> used) {
        for (int i = 0; i < ledger.size(); i++) {
            if (used.contains(i)) {
                continue;
            }
            if (matches(evidence, ledger.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(SelfReviewEvidenceParser.Evidence evidence, SandboxCallLedger.Entry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.exitCode() != evidence.exitCode() || entry.truncated() != evidence.truncated()) {
            return false;
        }
        if (!commandMatches(evidence.command(), entry.command())) {
            return false;
        }
        return digestMatches(evidence.digest(), entry.digest());
    }

    /** 命令按前缀方向匹配（台账条目已截断到 200 字符并可能带 {@code ...}）。 */
    static boolean commandMatches(String claimed, String ledgerCommand) {
        String left = commandKey(ledgerCommand);
        String right = commandKey(claimed);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.startsWith(right) || right.startsWith(left);
    }

    /** digest 为空（模型未计算）时不做一致性判定（降级为三项比对）。 */
    static boolean digestMatches(String claimed, String ledgerDigest) {
        String right = normalizeDigest(claimed);
        if (right.isEmpty()) {
            return true;
        }
        String left = normalizeDigest(ledgerDigest);
        return !left.isEmpty() && left.equals(right);
    }

    private static String commandKey(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.endsWith("...") && trimmed.length() > 3) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        return trimmed.length() <= COMMAND_KEY_CHARS ? trimmed : trimmed.substring(0, COMMAND_KEY_CHARS).trim();
    }

    private static String normalizeDigest(String digest) {
        if (digest == null) {
            return "";
        }
        String value = digest.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("sha256:")) {
            value = value.substring("sha256:".length()).trim();
        }
        return value;
    }
}
