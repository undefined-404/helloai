package com.helloai.core.review.quality;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateFinding;
import com.helloai.core.agent.quality.gate.GateOutcome;
import com.helloai.core.agent.quality.gate.GateSeverity;
import com.helloai.core.agent.quality.gate.QualityGate;
import com.helloai.core.review.support.ReviewFailureSignature;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @see ReviewFailureSignature
 */
@Component
public class RepeatedFailureGate implements QualityGate<List<Map<String, Object>>> {

    /** 闸门标识（事件 / 日志归因）。 */
    public static final String GATE_NAME = "repeated_failure_signature";

    /** 证据 basis：评分停滞 + 文本重复（双条件命中）。 */
    public static final String BASIS_SCORE_STALL_AND_TEXT_REPEAT = "score_stall_and_text_repeat";

    /** 证据 basis：评分不可比，仅文本重复命中。 */
    public static final String BASIS_TEXT_REPEAT = "text_repeat";

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
}
