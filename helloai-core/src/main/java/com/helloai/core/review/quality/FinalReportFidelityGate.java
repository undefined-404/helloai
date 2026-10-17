package com.helloai.core.review.quality;

import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateFinding;
import com.helloai.core.agent.quality.gate.GateOutcome;
import com.helloai.core.agent.quality.gate.GateSeverity;
import com.helloai.core.agent.quality.gate.QualityGate;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.review.support.FinalReportFidelityChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p><b>S-9 沙箱执行链机械判定接入（沙箱执行链设计方案 §3.5/§4，2026-10-17）</b>：
 * 报告正文<b>声明脚本类交付物</b>（存在可执行语言围栏 {@code ```sh / bash / python / py / ps1 / powershell}）
 * 时，把围栏内脚本正文提取送 {@link SandboxExecutionService#execute} 实测可执行性，
 * 结果交 {@link FinalReportFidelityChecker#checkScriptExecutability}（纯静态）判定：</p>
 * <ul>
 *   <li><b>证据有效</b>（exitCode=0 且未截断）⇒ 不新增违规（脚本确实跑通，作为 Reviewer 一手证据）；</li>
 *   <li><b>证据不足 / 执行失败</b>（沙箱未启用 / 不可达 / exitCode!=0 / 截断）⇒ HARD 机械驳回，
 *       同 {@code shell_reference} 语义——「报告声明脚本可执行却拿不出可执行证据」不给 LLM 放过机会。</li>
 * </ul>
 *
 * <p><b>零行为变化边界（方案 C 承诺）</b>：</p>
 * <ul>
 *   <li>沙箱未接线（{@code sandboxExecutionService == null}，本类无参构造 / 测试路径）⇒ S-9 不触发；</li>
 *   <li>报告正文<b>无</b>可执行语言围栏（非脚本交付物）⇒ 不调用沙箱，既有判定逐字不变；</li>
 *   <li>脚本围栏验证失败时降级为「不验证」（catch 后返回空），不阻断审查主链。</li>
 * </ul>
 */
@Slf4j
@Component
public class FinalReportFidelityGate implements QualityGate<String> {

    /** 闸门标识（事件 / 日志归因）。 */
    public static final String GATE_NAME = "final_report_fidelity";

    /** 可执行语言围栏（S-9：报告声明脚本类交付物的信号）。 */
    private static final List<String> EXECUTABLE_FENCE_LANGUAGES = List.of(
            "sh", "bash", "python", "py", "ps1", "powershell");

    /** 脚本围栏提取：{@code ```lang\n...```}（DOTALL 非贪婪到首个闭合围栏）。 */
    private static final Pattern SCRIPT_FENCE = Pattern.compile(
            "```(?:" + String.join("|", EXECUTABLE_FENCE_LANGUAGES) + ")[ \\t]*\\n(.*?)```",
            Pattern.DOTALL);

    /** 单次审查最多沙箱执行的脚本数（防滥用；超出部分不执行、不追加 HARD，仅日志提示）。 */
    private static final int MAX_SCRIPTS_VERIFIED = 3;

    /** 沙箱执行通道（S-9 注入点；null = 未接线 ⇒ 不触发脚本验证，行为与既有逐字一致）。 */
    private final SandboxExecutionService sandboxExecutionService;

    /** 无沙箱接线构造（测试 / 未启用路径：S-9 不触发，行为零变化）。 */
    public FinalReportFidelityGate() {
        this(null);
    }

    /** Spring 注入构造（S-9：沙箱执行通道接线后才启用脚本可执行性验证）。 */
    @Autowired
    public FinalReportFidelityGate(SandboxExecutionService sandboxExecutionService) {
        this.sandboxExecutionService = sandboxExecutionService;
    }

    @Override
    public String name() {
        return GATE_NAME;
    }

    @Override
    public GateDecision evaluate(String report) {
        FinalReportFidelityChecker.Result result = FinalReportFidelityChecker.check(report);
        List<FinalReportFidelityChecker.Violation> violations = new ArrayList<>(result.violations());
        // S-9：报告声明脚本类交付物（正文含可执行语言围栏）→ 提取脚本正文送沙箱验证可执行性
        if (sandboxExecutionService != null) {
            violations.addAll(evaluateScriptExecutability(report));
        }
        List<GateFinding> findings = new ArrayList<>(violations.size());
        for (FinalReportFidelityChecker.Violation v : violations) {
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

    /**
     * S-9：提取报告正文中的可执行语言围栏脚本，逐个送沙箱实测可执行性。
     *
     * <p>无脚本围栏 ⇒ 返回空（非脚本交付物，行为零变化）。脚本验证异常 ⇒ 降级为不验证
     * （catch 返回空，不阻断审查主链）。</p>
     */
    private List<FinalReportFidelityChecker.Violation> evaluateScriptExecutability(String report) {
        List<FinalReportFidelityChecker.Violation> out = new ArrayList<>();
        try {
            List<String> scripts = extractScriptFences(report);
            if (scripts.isEmpty()) {
                return out;
            }
            int verified = 0;
            for (String script : scripts) {
                if (verified >= MAX_SCRIPTS_VERIFIED) {
                    break;
                }
                verified++;
                ExecuteResponse execution = sandboxExecutionService.execute(null, script, null);
                out.addAll(FinalReportFidelityChecker.checkScriptExecutability(script, execution).violations());
            }
            if (scripts.size() > MAX_SCRIPTS_VERIFIED) {
                log.info("FinalReportFidelityGate: 报告含 {} 个脚本围栏，仅沙箱实测前 {} 个（防滥用）",
                        scripts.size(), MAX_SCRIPTS_VERIFIED);
            }
        } catch (Exception e) {
            // 沙箱验证自身异常不阻断审查（回退既有判定；与 checker 容错哲学一致）
            log.warn("FinalReportFidelityGate: 脚本可执行性验证异常，降级为不验证: err={}", e.getMessage());
        }
        return out;
    }

    /** 提取报告正文中全部可执行语言围栏的脚本正文（trim，忽略空白围栏）。 */
    private static List<String> extractScriptFences(String report) {
        List<String> scripts = new ArrayList<>();
        if (report == null || report.isBlank()) {
            return scripts;
        }
        Matcher matcher = SCRIPT_FENCE.matcher(report);
        while (matcher.find()) {
            String body = matcher.group(1);
            if (body != null && !body.isBlank()) {
                scripts.add(body.trim());
            }
        }
        return scripts;
    }

    private static GateSeverity toSeverity(FinalReportFidelityChecker.Severity severity) {
        return severity == FinalReportFidelityChecker.Severity.HARD ? GateSeverity.HARD : GateSeverity.SOFT;
    }
}
