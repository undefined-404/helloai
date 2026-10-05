package com.helloai.core.review.quality;

import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateFinding;
import com.helloai.core.agent.quality.gate.GateOutcome;
import com.helloai.core.agent.quality.gate.GateSeverity;
import com.helloai.core.agent.quality.gate.QualityGate;
import com.helloai.core.review.support.FinalReportFidelityChecker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 最终报告交付保真闸门（RM12 收口：把既有机械前置门适配为统一 {@link QualityGate}）。
 *
 * <p><b>形态</b>：按用户拍板的「纯静态工具<b>保持静态</b>、由 Gate 持有」——
 * {@link FinalReportFidelityChecker}（纯静态、零 Spring 依赖）<b>原样不动</b>，
 * 本类仅持有它并把其 {@code Result} 适配为统一 {@link GateDecision}。</p>
 *
 * <p><b>行为等价</b>（收口不改变任何判定）：</p>
 * <ul>
 *   <li>含 HARD 违规 ⇒ {@link GateOutcome#BLOCK}（等价原 {@code hasHard()}）；</li>
 *   <li>无 HARD 但有 SOFT ⇒ {@link GateOutcome#ADVISE}（等价原 {@code hasSoft()}）；</li>
 *   <li>无任何违规 ⇒ {@link GateOutcome#PASS}；</li>
 *   <li>发现项顺序与渲染格式与 {@code Result.issueList} 逐字一致（见 {@link GateDecision#detailList}）。</li>
 * </ul>
 */
@Component
public class FinalReportFidelityGate implements QualityGate<String> {

    /** 闸门标识（事件 / 日志归因）。 */
    public static final String GATE_NAME = "final_report_fidelity";

    @Override
    public String name() {
        return GATE_NAME;
    }

    @Override
    public GateDecision evaluate(String report) {
        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);
        List<GateFinding> findings = new ArrayList<>(result.violations().size());
        for (FinalReportFidelityChecker.Violation v : result.violations()) {
            findings.add(new GateFinding(toSeverity(v.severity()), v.rule(), v.detail()));
        }
        GateOutcome outcome;
        if (findings.stream().anyMatch(f -> f.severity() == GateSeverity.HARD)) {
            outcome = GateOutcome.BLOCK;
        } else if (findings.isEmpty()) {
            outcome = GateOutcome.PASS;
        } else {
            outcome = GateOutcome.ADVISE;
        }
        return new GateDecision(outcome, findings);
    }

    private static GateSeverity toSeverity(FinalReportFidelityChecker.Severity severity) {
        return severity == FinalReportFidelityChecker.Severity.HARD ? GateSeverity.HARD : GateSeverity.SOFT;
    }
}
