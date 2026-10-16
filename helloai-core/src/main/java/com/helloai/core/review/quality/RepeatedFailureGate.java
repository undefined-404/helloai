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
 * <p><b>只收口「判定」，不搬「处置」</b>：本闸门只回答「本轮是否与上轮构成同一结构性失败」，
 * 事件落库 / 入死信 / 人工介入标记 / 状态流转仍留在 {@code SubTaskReviewServiceImpl} 原处——
 * 处置涉及 {@code SubTask} 等业务对象，属编排职责（§7.3 避免无谓搬运）。</p>
 *
 * <p><b>形态</b>：按用户拍板的「纯静态工具保持静态、由 Gate 持有」——
 * {@link ReviewFailureSignature} 原样不动（纯静态、零 Spring 依赖），本类持有它并补充阈值判定。</p>
 *
 * <p><b>判定逻辑（与既有实现逐字一致，R1 二次修订）</b>：</p>
 * <ul>
 *   <li>主判据：{@code scoreStalled && textRepeated} —— 评分未严格提升 <b>且</b>
 *       两轮 issues 字符 bigram 相似度 ≥ 阈值，<b>同时成立</b>才判「同一结构性失败」；</li>
 *   <li>真机 687 连续两次误伤（round6 sim=0.17 / round8 sim=0.236，均为全新问题）
 *       即因原「评分未提升」单判据过强所致，故加相似度第二条件；</li>
 *   <li>{@code score} 任一轮缺失 ⇒ 退回「两轮文本高度相似」单条件兜底。</li>
 * </ul>
 *
 * <p><b>2026-10-16 增补（`LOG-20261011-007` B 收敛熔断）</b>：新增<b>先判</b>的
 * 「执行者自报未完成连续 N 轮」判据（basis = {@link #BASIS_SELF_REPORTED_STALL}），
 * 专门补上面双条件判据的<b>盲区</b>——真停滞形态（评分恒定 + 驳回意见整篇重写）全文相似度仅
 * 0.18~0.25，恰不被 0.85 阈值短路，只能靠返工预算封顶（tku-e2e-01 空转 4 轮烧 346K tokens）。
 * 信号取执行者轮报的结构化 {@code STATUS}（{@code REF-8.2.2} 输出契约），属<b>句法级、零阈值标定</b>：
 * 连续 N 轮自认 {@code BLOCKED}/{@code PARTIAL} 且评分不提升 ⇒ 结构性做不到。
 * <b>刻意不走「驳回意见文本相似度」路</b>（含「换比较对象为 {@code [defect]} 标签」）——
 * 该路已被 `doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md` §15.3
 * 用真实语料实测证伪（标签相似度比全文更低）。
 * 命中时随证据下发 {@link #ANNOTATION_CAPABILITY_OR_INPUT_GAP} 归因标注。
 * 未命中则<b>逐字落回</b>上述原判据 ⇒ 原行为零变化。</p>
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
     * 证据 basis：<b>执行者自报未完成连续 N 轮</b>（`LOG-20261011-007` B 收敛熔断，2026-10-16 新增）。
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
     * <p><b>为什么不用「驳回意见相似度」判同因（重要，勿重蹈）</b>：既有两轮判据比较 issues 全文，
     * 而真停滞形态下 LLM 每轮把四元组整篇重写，全文相似度实测仅 0.18~0.25 ⇒ 恰好不被短路。
     * 曾考虑「换比较对象为 {@code [defect]} 标签」，但 `doc/review/HelloAI 架构V2进度与质量审计报告
     * （2026-09-30）.md` §15.3 用<b>真实语料</b>实测该口径<b>更差</b>（首条 {@code [defect]} Jaccard
     * 仅 0.1203 / 0.1620，低于全文的 0.2537 / 0.1927；根因 = 三轮用三套不同编号指代同一批缺失项，
     * 字符级 bigram 捕捉不到语义相同）⇒ 该路已被证伪。本判据改用<b>句法级</b>信号。</p>
     *
     * <p><b>信号与步骤</b>：</p>
     * <ol>
     *   <li>取末尾 N 轮（N = {@code autoReviewSelfStallRounds}，默认 3）；</li>
     *   <li>逐轮读 {@code executorStatus}（执行者轮报 {@code STATUS:} 值，由
     *       {@code SubTaskReviewServiceImpl.rejectAndRework} 解析写入）。
     *       <b>任一轮缺失或空白 ⇒ 整条判据不适用，返回 {@code null}</b>（落回既有两轮判据）；</li>
     *   <li>全部轮次的状态（大小写不敏感）∈ {@link #STALLED_EXECUTOR_STATUSES}
     *       = {@code BLOCKED} / {@code PARTIAL} —— 即执行者自己连续 N 轮说「没做完」；</li>
     *   <li>窗口级评分<b>未严格提升</b>（首轮 → 末轮；评分不可比时不作要求，与原判据同口径）
     *       —— 有实质进展则放行，防误伤「正在收敛」的返工。</li>
     * </ol>
     *
     * <p><b>为什么「任一状态缺失即整条不适用」</b>：本判据的<b>定义</b>建立在执行者显式自报之上——
     * 状态缺失（外部 CLI_CLIENT 执行者不产出围栏、或旧数据无该字段）时不是「判定不成立」，
     * 而是「判据不适用」。此时若勉强用其他信号替代，就是又回到被证伪的文本相似度路。
     * 故直接让位给原判据。</p>
     *
     * <p><b>为什么要求 score 不提升</b>：本判据的动作是「提前转死信 + 人工介入」，
     * 比返工预算（{@code autoReviewMaxRework}）更早。若评分仍在上升，说明执行者还在收敛，
     * 提前熔断会掐掉本可自愈的返工 ⇒ 保留 score 条件作为「有进展即放行」的闸。
     * 换言之本判据早于返工预算，但不早于「实质进展」。</p>
     *
     * @param history 核验历史（含本轮，按轮次升序）
     * @return 命中返回 BLOCK 决策（证据含 basis/round/rounds/scoreTrend/executorStatuses/annotation）；
     *         <b>未命中或前提不足返回 {@code null}</b>（调用方落回既有两轮判据）
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
