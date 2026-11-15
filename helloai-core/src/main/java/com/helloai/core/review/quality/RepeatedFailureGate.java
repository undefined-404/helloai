package com.helloai.core.review.quality;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateFinding;
import com.helloai.core.agent.quality.gate.GateOutcome;
import com.helloai.core.agent.quality.gate.GateSeverity;
import com.helloai.core.agent.quality.gate.QualityGate;
import com.helloai.core.review.support.ReviewFailureSignature;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 重复失败闸门（RM12 收口：把既有「重复失败短路判定」适配为统一 {@link QualityGate}）。
 *
 * <p>只收口「判定」不搬「处置」：事件落库/入死信/人工介入/状态流转仍留 {@code SubTaskReviewServiceImpl}（编排职责）。
 * 形态：纯静态 {@link ReviewFailureSignature} 原样不动，本类持有它并补阈值判定。</p>
 *
 * <p>主判据 {@code scoreStalled && textRepeated}：评分未严格提升<b>且</b>两轮 issues bigram 相似度 ≥ 阈值，同时成立才判同一结构性失败
 * （单靠「评分未提升」过强会误伤全新问题）；{@code score} 任一轮缺失退回「文本高度相似」单条件兜底。</p>
 *
 * <p>增补（`LOG-20261011-007` B 收敛熔断）：先判「执行者自报未完成连续 N 轮」（{@link #BASIS_SELF_REPORTED_STALL}），
 * 补双条件盲区——真停滞（评分恒定 + 驳回意见整篇重写）全文相似度低、不被阈值短路。信号取轮报结构化 {@code STATUS}（`REF-8.2.2`），
 * 句法级零阈值：连续 N 轮自认 {@code BLOCKED}/{@code PARTIAL} 且评分不提升 ⇒ 结构性做不到。刻意不走驳回意见相似度路（真实语料已证伪标签相似度更低）；
 * 命中下发 {@link #ANNOTATION_CAPABILITY_OR_INPUT_GAP} 归因，未命中逐字落回原判据。</p>
 *
 * @see ReviewFailureSignature
 * @see com.helloai.core.shared.util.VerificationFence
 */
@Component
public class RepeatedFailureGate implements QualityGate<List<Map<String, Object>>> {

    /** 闸门标识（事件 / 日志归因）。 */
    public static final String GATE_NAME = "repeated_failure_signature";

    /** 证据 basis：评分停滞 + 文本重复（双条件命中）。 */
    public static final String BASIS_SCORE_STALL_AND_TEXT_REPEAT = "score_stall_and_text_repeat";

    /** 证据 basis：评分不可比，仅文本重复命中。 */
    public static final String BASIS_TEXT_REPEAT = "text_repeat";

    /**
     * 证据 basis：<b>执行者自报未完成连续 N 轮</b>（`LOG-20261011-007` B 收敛熔断）。
     *
     * <p>信号源 = 各轮 {@code reviewHistory[].executorStatus}（由执行者轮报
     * {@code VERIFICATION:}/{@code STATUS:} 围栏解析而来，见
     * {@code SubTaskReviewServiceImpl.rejectAndRework}）；判据见 {@link #evaluateSelfReportedStall}。</p>
     */
    public static final String BASIS_SELF_REPORTED_STALL = "self_reported_stall";

    /**
     * 「自认未完成」的轮报状态集合（大小写不敏感，{@code REF-8.2.2} 契约取值域
     * {@code DONE|PARTIAL|BLOCKED} 中非 {@code DONE} 的两项）。
     *
     * <p>{@code BLOCKED} = 执行者显式自认受阻；{@code PARTIAL} = 显式自认只完成一部分。
     * 两者都等于「执行者自己说：这活我没做完」——连续 N 轮如此且评分不提升，
     * 即结构性做不到（而非质量波动），提前转人工。</p>
     */
    public static final Set<String> STALLED_EXECUTOR_STATUSES = Set.of("BLOCKED", "PARTIAL");

    /**
     * 命中 {@link #BASIS_SELF_REPORTED_STALL} 时的归因标注（`LOG-20261011-007` B）。
     *
     * <p>该形态（连续多轮同一驳回原因、评分不提升）在实务上高概率是
     * 「<b>执行者能力上做不到 / 输入缺失</b>」，而非「质量不达标」——
     * 继续返工只是空转。故随证据一并下发，供处置侧写进人工介入 payload 与对话流，
     * 让「转人工」带上可读原因，而不是只呈现一个冷冰冰的 reason 码。</p>
     */
    public static final String ANNOTATION_CAPABILITY_OR_INPUT_GAP = "executor_incapable_or_input_missing";

    private final AgentDispatchProperties dispatchProperties;

    public RepeatedFailureGate(AgentDispatchProperties dispatchProperties) {
        this.dispatchProperties = dispatchProperties;
    }

    @Override
    public String name() {
        return GATE_NAME;
    }

    /**
     * 判定最近两轮核验是否为同一结构性失败。
     *
     * @param history 核验历史（末尾两条 = 上轮 + 本轮）；不足两条 → {@link GateDecision#skip()}
     * @return 命中返回 {@link GateOutcome#BLOCK}（证据含 basis / round / scoreTrend / similarity / threshold / issues）；
     *         未命中或前提不足返回 {@link GateDecision#pass()} / {@link GateDecision#skip()}
     */
    @Override
    public GateDecision evaluate(List<Map<String, Object>> history) {
        if (history == null || history.size() < 2) {
            return GateDecision.skip();
        }
        // `LOG-20261011-007` B：先判「执行者自报未完成连续 N 轮」（句法级信号，早于返工预算），
        // 未命中时再落回下方既有的两轮全文双条件判据（原行为逐字不变）。
        GateDecision stall = evaluateSelfReportedStall(history);
        if (stall != null) {
            return stall;
        }
        Map<String, Object> prev = history.get(history.size() - 2);
        Map<String, Object> curr = history.get(history.size() - 1);
        String prevIssues = ReviewFailureSignature.normalize(prev.get("issues"));
        String currIssues = ReviewFailureSignature.normalize(curr.get("issues"));
        if (prevIssues.isBlank() || currIssues.isBlank()) {
            return GateDecision.pass();
        }
        Integer prevScore = ReviewFailureSignature.asScore(prev.get("score"));
        Integer currScore = ReviewFailureSignature.asScore(curr.get("score"));
        boolean scoreComparable = prevScore != null && currScore != null;
        double similarity = ReviewFailureSignature.similarity(prevIssues, currIssues);
        double threshold = dispatchProperties.getAutoReviewRepeatFailureSimilarity();
        boolean scoreStalled = !scoreComparable || currScore <= prevScore;
        boolean textRepeated = similarity >= threshold;
        if (!(scoreStalled && textRepeated)) {
            // 评分提升，或问题已变（文本不再相似）：模型仍有实质进展，继续正常返工（防误伤迭代）
            return GateDecision.pass();
        }
        String basis = scoreComparable ? BASIS_SCORE_STALL_AND_TEXT_REPEAT : BASIS_TEXT_REPEAT;
        String scoreTrend = scoreComparable ? prevScore + "->" + currScore : "n/a";
        int round = curr.get("round") instanceof Number n ? n.intValue() : history.size();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("basis", basis);
        evidence.put("round", round);
        evidence.put("scoreTrend", scoreTrend);
        evidence.put("similarity", similarity);
        evidence.put("threshold", threshold);
        evidence.put("issues", currIssues);

        GateFinding finding = new GateFinding(GateSeverity.HARD, GATE_NAME,
                "连续两轮同一结构性失败（basis=" + basis + ", " + scoreTrend
                        + ", similarity=" + String.format("%.3f", similarity) + "）");
        return new GateDecision(GateOutcome.BLOCK, List.of(finding), evidence);
    }

    /**
     * `LOG-20261011-007` B 收敛熔断判定：<b>执行者自报未完成连续 N 轮</b>是否成立。
     *
     * <p>不用驳回意见相似度判同因：真停滞下 LLM 每轮整篇重写四元组，全文/标签相似度均低（真实语料已证伪），改用句法级信号。</p>
     *
     * <p>信号与步骤：① 取末尾 N 轮（N = {@code autoReviewSelfStallRounds}，默认 3）；② 逐轮读 {@code executorStatus}（轮报 {@code STATUS:}，
     * 由 {@code rejectAndRework} 解析写入），任一轮缺失/空白 ⇒ 整条不适用返回 {@code null}（落回两轮判据）；③ 全部状态（大小写不敏感）
     * ∈ {@link #STALLED_EXECUTOR_STATUSES}（{@code BLOCKED}/{@code PARTIAL}）；④ 窗口级评分未严格提升（首→末，不可比时不作要求）。</p>
     *
     * <p>状态缺失即不适用：判据建立在执行者显式自报上，缺失（外部 CLI 不产围栏/旧数据无字段）是「不适用」非「不成立」，勉强替代又回到被证伪的相似度路。
     * 要求 score 不提升：本判据提前转死信早于返工预算，评分仍升说明还在收敛，提前熔断会掐掉可自愈返工，故留 score 作「有进展即放行」闸。</p>
     *
     * @param history 核验历史（含本轮，按轮次升序）
     * @return 命中返回 BLOCK 决策（证据含 basis/round/rounds/scoreTrend/executorStatuses/annotation）；
     *         <b>未命中或前提不足返回 {@code null}</b>（用方落回既有两轮判据）
     */
    private GateDecision evaluateSelfReportedStall(List<Map<String, Object>> history) {
        if (!dispatchProperties.isAutoReviewSelfStallEnabled()) {
            return null;
        }
        int rounds = dispatchProperties.getAutoReviewSelfStallRounds();
        if (rounds < 2 || history.size() < rounds) {
            return null;
        }
        List<Map<String, Object>> window = history.subList(history.size() - rounds, history.size());

        List<String> statuses = new ArrayList<>(rounds);
        for (Map<String, Object> round : window) {
            Object raw = round.get("executorStatus");
            String status = raw == null ? "" : raw.toString().trim();
            if (status.isEmpty()) {
                // 执行者未自报状态（外部执行者不产出围栏 / 旧轮次无该字段）⇒ 判据不适用
                return null;
            }
            String upper = status.toUpperCase(Locale.ROOT);
            if (!STALLED_EXECUTOR_STATUSES.contains(upper)) {
                // 至少一轮自认 DONE（或未知取值）⇒ 不构成「连续自认未完成」
                return null;
            }
            statuses.add(upper);
        }

        Integer firstScore = ReviewFailureSignature.asScore(window.get(0).get("score"));
        Integer lastScore = ReviewFailureSignature.asScore(window.get(window.size() - 1).get("score"));
        if (firstScore != null && lastScore != null && lastScore > firstScore) {
            // 评分有实质提升 ⇒ 不提前熔断（防误伤迭代；原两轮判据同此纪律）
            return null;
        }
        String scoreTrend = (firstScore != null && lastScore != null)
                ? firstScore + "->" + lastScore : "n/a";
        int round = lastRoundNo(window, history);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("basis", BASIS_SELF_REPORTED_STALL);
        evidence.put("round", round);
        evidence.put("rounds", rounds);
        evidence.put("scoreTrend", scoreTrend);
        evidence.put("executorStatuses", String.join("/", statuses));
        evidence.put("annotation", ANNOTATION_CAPABILITY_OR_INPUT_GAP);

        GateFinding finding = new GateFinding(GateSeverity.HARD, GATE_NAME,
                "连续 " + rounds + " 轮执行者自报未完成（basis=" + BASIS_SELF_REPORTED_STALL
                        + ", statuses=" + String.join("/", statuses) + ", " + scoreTrend + "）");
        return new GateDecision(GateOutcome.BLOCK, List.of(finding), evidence);
    }

    /** 取窗口末轮的 round 号；缺失时回退历史长度（与两轮判据同口径）。 */
    private static int lastRoundNo(List<Map<String, Object>> window, List<Map<String, Object>> history) {
        Object raw = window.get(window.size() - 1).get("round");
        return raw instanceof Number n ? n.intValue() : history.size();
    }
}
