package com.helloai.core.agent.quality.gate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量闸门判定结果：结论 + 发现项清单 + 证据（目标架构 B4「Quality Gate 泛化」/ RM12 统一决策载体）。
 *
 * <p><b>设计约束</b>：</p>
 * <ul>
 *   <li>纯数据：仅依赖 JDK 类型 + 本包枚举，<b>不依赖任何业务域</b>
 *       （因此可被 review / agent / planner 三域顺向引用）；</li>
 *   <li>{@link #detailList(GateSeverity)} 的渲染格式与既有
 *       {@code FinalReportFidelityChecker.Result#issueList} <b>逐字一致</b>——
 *       接线后对外事件 / 日志内容零变化；</li>
 *   <li>{@link #evidence} 为「可机读的判定依据」（如 basis / scoreTrend / similarity / threshold），
 *       供调用方原样落入事件 payload，<b>不承载业务对象</b>。</li>
 * </ul>
 */
public record GateDecision(GateOutcome outcome, List<GateFinding> findings, Map<String, Object> evidence) {

    public GateDecision {
        findings = findings == null ? List.of() : List.copyOf(findings);
        // 保留插入顺序；允许 null 值（证据项可能缺省），故不用 Map.copyOf
        evidence = evidence == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    /** 无证据的便捷构造（多数闸门只用结论 + 发现项）。 */
    public GateDecision(GateOutcome outcome, List<GateFinding> findings) {
        this(outcome, findings, Map.of());
    }

    /** 通过（无发现项）。 */
    public static GateDecision pass() {
        return new GateDecision(GateOutcome.PASS, List.of(), Map.of());
    }

    /** 跳过（输入缺失 / 判定不适用）。 */
    public static GateDecision skip() {
        return new GateDecision(GateOutcome.SKIP, List.of(), Map.of());
    }

    /** 是否拦截（命中硬判据，调用方可据此阻断 / 驳回）。 */
    public boolean blocks() {
        return outcome == GateOutcome.BLOCK;
    }

    /** 是否存在指定级别的问题项。 */
    public boolean hasSeverity(GateSeverity severity) {
        return findings.stream().anyMatch(f -> f.severity() == severity);
    }

    /** 读取证据项（不存在返回 {@code null}）。 */
    public Object evidence(String key) {
        return evidence.get(key);
    }

    /**
     * 按级别渲染为编号清单（可注入返工 Prompt / 落库）。
     *
     * <p>格式与既有机械校验 {@code issueList} 逐字一致：
     * 逐行 {@code 序号. [规则名] 描述}，末尾 {@code trim()}。</p>
     */
    public String detailList(GateSeverity severity) {
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (GateFinding f : findings) {
            if (f.severity() != severity) {
                continue;
            }
            sb.append(idx++).append(". [").append(f.rule()).append("] ").append(f.detail()).append('\n');
        }
        return sb.toString().trim();
    }
}
