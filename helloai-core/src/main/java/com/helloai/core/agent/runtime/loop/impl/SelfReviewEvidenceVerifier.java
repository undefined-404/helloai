package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 自审证据对账（`SR-2`，纯静态）。
 *
 * <p><b>事实源</b>：以 {@link SandboxCallLedger} 的平台侧记录为准，模型自填的
 * {@code EVIDENCE} 块只作对照。自填条目在台账里找不到对应执行事实 ⇒
 * {@link Outcome#MISMATCH}（{@code self_reported_mismatch}）。</p>
 *
 * <p><b>两条对账路径</b>（`SL-5`）：</p>
 * <ul>
 *     <li><b>引用式</b>（{@code callId} 非空）：按条目坐标精确命中；同时自报了旧字段而<b>与台账矛盾</b>
 *         的，仍判 mismatch —— 引用真实条目 + 编造结果正是本项要堵的伪造形态。</li>
 *     <li><b>旧格式</b>（无 {@code callId}）：沿用 `SR-2` 的四字段比对（命令前缀方向 + exitCode +
 *         truncated + digest），仅作一版兼容。</li>
 * </ul>
 *
 * <p><b>来源与轮次过滤</b>（`SL-5`）：</p>
 * <ul>
 *     <li>旧格式匹配<b>只认作者的条目</b>（{@code origin=executor}）—— reviewer 的验证命令与作者
 *         命令文本相同时，不能顶替作者证据。</li>
 *     <li>引用式允许引用非作者条目，但标 {@code evidence_origin_non_author}（warn，不判失败）：
 *         显式引用是模型的声明，平台据实记录来源，交给审查侧判断。</li>
 *     <li>旧格式匹配优先<b>本轮</b>（{@code round} 相等）条目，本轮无匹配才回退全账本，命中后标
 *         {@code evidence_round_crossed}（warn）：让「上一轮的同类命令」不再静默冒充本轮。</li>
 * </ul>
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
     * @param matched      已证实命中的台账条目（`SL-5`；闸门据此判「引用的条目里有没有成功执行」）
     * @param notes        告警信号（`SL-5`：非作者来源 / 跨轮命中；不进失败判据）
     */
    public record Result(Outcome outcome, int claimedCount, int ledgerCount, List<String> mismatches,
                         List<SandboxCallLedger.Entry> matched, List<String> notes) {

        public Result {
            mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
            matched = matched == null ? List.of() : List.copyOf(matched);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /** 事件 payload 里最多列出的不一致条数。 */
    public static final int MAX_MISMATCH_DETAILS = 5;

    /** 事件 payload 里最多列出的告警条数（与 mismatch 同口径，防无限增长）。 */
    public static final int MAX_NOTE_DETAILS = 5;

    /** 台账命令预览上限（与 {@link SandboxCallLedger#commandPreview} 同口径）。 */
    private static final int COMMAND_KEY_CHARS = 200;

    private SelfReviewEvidenceVerifier() {
    }

    /**
     * 对账（无轮次上下文）。
     *
     * @param parsed 解析结果（可 null）
     * @param ledger 该子任务的台账记录（按时间升序；可空）
     */
    public static Result verify(SelfReviewEvidenceParser.Parsed parsed, List<SandboxCallLedger.Entry> ledger) {
        return verify(parsed, ledger, 0);
    }

    /**
     * 对账。
     *
     * @param parsed       解析结果（可 null）
     * @param ledger       该子任务的台账记录（按时间升序；可空）
     * @param currentRound 当前执行轮次（旧格式匹配优先本轮；{@code <=0} 表示无轮次上下文）
     */
    public static Result verify(SelfReviewEvidenceParser.Parsed parsed, List<SandboxCallLedger.Entry> ledger,
                                int currentRound) {
        List<SandboxCallLedger.Entry> entries = ledger == null ? List.of() : ledger;
        if (parsed == null || parsed.status() == SelfReviewEvidenceParser.Status.ABSENT) {
            return emptyResult(Outcome.MISSING, entries);
        }
        if (parsed.status() == SelfReviewEvidenceParser.Status.MALFORMED) {
            return emptyResult(Outcome.MALFORMED, entries);
        }
        List<SelfReviewEvidenceParser.Evidence> claimed = parsed.entries();
        if (claimed.isEmpty()) {
            return emptyResult(Outcome.MISSING, entries);
        }
        Set<Integer> used = new HashSet<>();
        List<String> mismatches = new ArrayList<>();
        List<SandboxCallLedger.Entry> matched = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (SelfReviewEvidenceParser.Evidence evidence : claimed) {
            if (evidence.hasCallId()) {
                verifyReference(evidence, entries, used, currentRound, mismatches, matched, notes);
            } else {
                verifyLegacy(evidence, entries, used, currentRound, mismatches, matched, notes);
            }
        }
        Outcome outcome = mismatches.isEmpty() ? Outcome.VERIFIED : Outcome.MISMATCH;
        return new Result(outcome, claimed.size(), entries.size(), mismatches, matched, notes);
    }

    private static Result emptyResult(Outcome outcome, List<SandboxCallLedger.Entry> entries) {
        return new Result(outcome, 0, entries.size(), List.of(), List.of(), List.of());
    }

    /** 引用式对账：callId 精确命中；自报旧字段与台账矛盾时仍判不一致。 */
    private static void verifyReference(SelfReviewEvidenceParser.Evidence evidence,
                                        List<SandboxCallLedger.Entry> ledger, Set<Integer> used,
                                        int currentRound, List<String> mismatches,
                                        List<SandboxCallLedger.Entry> matched, List<String> notes) {
        String callId = evidence.callId() == null ? "" : evidence.callId().trim();
        int index = findCallId(callId, ledger, used);
        if (index < 0) {
            addMismatch(mismatches, "引用 callId=" + callId + " 在台账中不存在或已被其它证据占用");
            return;
        }
        SandboxCallLedger.Entry entry = ledger.get(index);
        if (contradicts(evidence, entry)) {
            addMismatch(mismatches, "引用 callId=" + entry.callId() + " 但自报字段与台账矛盾（台账 exitCode="
                    + entry.exitCode() + " truncated=" + entry.truncated() + "，自报 exitCode="
                    + nullText(evidence.exitCode()) + " truncated=" + nullText(evidence.truncated()) + "）");
            return;
        }
        used.add(index);
        matched.add(entry);
        if (!isAuthor(entry)) {
            addNote(notes, "evidence_origin_non_author: 引用 callId=" + entry.callId()
                    + " 来源=" + entry.origin());
        }
        if (currentRound > 0 && entry.round() != currentRound) {
            addNote(notes, "evidence_round_crossed: 引用 callId=" + entry.callId()
                    + " 属第 " + entry.round() + " 轮（当前第 " + currentRound + " 轮）");
        }
    }

    /** 旧格式对账：只认作者条目；优先本轮，本轮无命中回退全账本并记跨轮告警。 */
    private static void verifyLegacy(SelfReviewEvidenceParser.Evidence evidence,
                                     List<SandboxCallLedger.Entry> ledger, Set<Integer> used,
                                     int currentRound, List<String> mismatches,
                                     List<SandboxCallLedger.Entry> matched, List<String> notes) {
        LegacyMatch hit = findLegacy(evidence, ledger, used, currentRound);
        if (hit == null) {
            addMismatch(mismatches, "自报 command=" + commandKey(evidence.command())
                    + " exitCode=" + nullText(evidence.exitCode())
                    + " truncated=" + nullText(evidence.truncated()) + " 在台账中无匹配记录");
            return;
        }
        used.add(hit.index());
        SandboxCallLedger.Entry entry = ledger.get(hit.index());
        matched.add(entry);
        if (hit.crossedRound()) {
            addNote(notes, "evidence_round_crossed: 自报 command=" + commandKey(evidence.command())
                    + " 命中第 " + entry.round() + " 轮条目（当前第 " + currentRound + " 轮）");
        }
    }

    /** 旧格式命中结果；{@code crossedRound} 表示只在本轮之外的条目里找到。 */
    private record LegacyMatch(int index, boolean crossedRound) {
    }

    private static LegacyMatch findLegacy(SelfReviewEvidenceParser.Evidence evidence,
                                          List<SandboxCallLedger.Entry> ledger, Set<Integer> used,
                                          int currentRound) {
        if (currentRound > 0) {
            int sameRound = scanLegacy(evidence, ledger, used, currentRound, true);
            if (sameRound >= 0) {
                return new LegacyMatch(sameRound, false);
            }
        }
        int any = scanLegacy(evidence, ledger, used, currentRound, false);
        if (any < 0) {
            return null;
        }
        return new LegacyMatch(any, currentRound > 0 && ledger.get(any).round() != currentRound);
    }

    private static int scanLegacy(SelfReviewEvidenceParser.Evidence evidence,
                                  List<SandboxCallLedger.Entry> ledger, Set<Integer> used,
                                  int currentRound, boolean requireSameRound) {
        for (int i = 0; i < ledger.size(); i++) {
            if (used.contains(i)) {
                continue;
            }
            SandboxCallLedger.Entry entry = ledger.get(i);
            if (!isAuthor(entry)) {
                continue;
            }
            if (requireSameRound && entry.round() != currentRound) {
                continue;
            }
            if (matches(evidence, entry)) {
                return i;
            }
        }
        return -1;
    }

    private static int findCallId(String callId, List<SandboxCallLedger.Entry> ledger, Set<Integer> used) {
        if (callId.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < ledger.size(); i++) {
            if (used.contains(i)) {
                continue;
            }
            SandboxCallLedger.Entry entry = ledger.get(i);
            if (entry != null && callId.equals(entry.callId())) {
                return i;
            }
        }
        return -1;
    }

    /** 引用条目的自报字段与台账是否矛盾（未自报的字段不参与判定）。 */
    private static boolean contradicts(SelfReviewEvidenceParser.Evidence evidence, SandboxCallLedger.Entry entry) {
        if (evidence.exitCode() != null && evidence.exitCode() != entry.exitCode()) {
            return true;
        }
        if (evidence.truncated() != null && evidence.truncated() != entry.truncated()) {
            return true;
        }
        if (hasText(evidence.command()) && !commandMatches(evidence.command(), entry.command())) {
            return true;
        }
        return hasText(evidence.digest()) && !digestMatches(evidence.digest(), entry.digest());
    }

    /** 旧格式三/四项比对（{@code callId} 缺失时走这里）。 */
    private static boolean matches(SelfReviewEvidenceParser.Evidence evidence, SandboxCallLedger.Entry entry) {
        if (entry == null || evidence.exitCode() == null || evidence.truncated() == null) {
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

    /** 作者条目（来源缺省按执行者；reviewer / platform 的条目不能顶替作者证据）。 */
    private static boolean isAuthor(SandboxCallLedger.Entry entry) {
        String origin = entry.origin();
        return origin == null || origin.isBlank() || SandboxCallOrigin.EXECUTOR.code().equals(origin);
    }

    private static void addMismatch(List<String> mismatches, String reason) {
        if (mismatches.size() < MAX_MISMATCH_DETAILS) {
            mismatches.add(reason);
        }
    }

    private static void addNote(List<String> notes, String note) {
        if (notes.size() < MAX_NOTE_DETAILS) {
            notes.add(note);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullText(Object value) {
        return value == null ? "未自报" : String.valueOf(value);
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
