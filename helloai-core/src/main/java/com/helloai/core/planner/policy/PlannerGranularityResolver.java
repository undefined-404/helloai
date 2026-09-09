package com.helloai.core.planner.policy;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.task.policy.TaskAgentPolicy;

import java.util.List;
import java.util.Map;

/**
 * Planner 拆解粒度决策器（G-010，rule-based，先于 LLM 自判）。
 *
 * <p>纯函数：入参为 task.agent_policy + 白名单内执行者的 accessType 列表
 * （由调用方经 {@code AgentService.listByIds} 一次批量查询后传入，不在本类查库），
 * 出粒度三档 + 执行者画像文案。可解释、可回归，矩阵单测全覆盖。</p>
 *
 * <p>决策矩阵（已拍板，见 {@code doc/design/Planner_Capability_Awareness.md} §1-D3）：
 * <ul>
 *   <li>全部 CLI_CLIENT（外部强执行者）→ COARSE；</li>
 *   <li>全部 API_KEY_LLM / WEB_BROWSER（内部兜底）→ FINE；</li>
 *   <li>混合（明确强弱并存）→ FINE（按弱者兜底，下限导向）；</li>
 *   <li>白名单为空（不限定）→ STANDARD（不确定执行者不默认细拆，保持现状）；</li>
 *   <li>difficulty=HIGH → 上移一档（COARSE→STANDARD→FINE），LOW/MEDIUM 不变。</li>
 * </ul>
 */
public final class PlannerGranularityResolver {

    private PlannerGranularityResolver() {
    }

    /** 决策结果：粒度 + 执行者画像文案（供 Promp 渲染 `{{GRANULARITY}}` / `{{EXECUTOR_PROFILE}}`）。 */
    public record GranularityDecision(PlannerGranularity granularity, String profile) {
    }

    /**
     * 依据任务策略与执行者画像定位拆解粒度。
     *
     * @param policy              task.agent_policy（可为 null）
     * @param executorAccessTypes 白名单内执行者的 accessType（可为 null/空；顺序无关）
     * @return 决策结果（永不返回 null）
     */
    public static GranularityDecision resolve(Map<String, Object> policy,
                                              List<AgentAccessType> executorAccessTypes) {
        List<Long> whitelist = TaskAgentPolicy.executorAgentIds(policy);
        TaskAgentPolicy.Difficulty difficulty = TaskAgentPolicy.difficulty(policy);

        PlannerGranularity base;
        String profile;
        if (whitelist.isEmpty()) {
            base = PlannerGranularity.STANDARD;
            profile = "不限定（平台自由选人）";
        } else {
            boolean hasStrong = false;
            boolean hasWeak = false;
            if (executorAccessTypes != null) {
                for (AgentAccessType t : executorAccessTypes) {
                    if (t == AgentAccessType.CLI_CLIENT) {
                        hasStrong = true;
                    } else if (t != null) {
                        hasWeak = true;
                    }
                }
            }
            if (hasStrong && !hasWeak) {
                base = PlannerGranularity.COARSE;
                profile = "外部 AI Agent（CLI_CLIENT）";
            } else if (!hasStrong && hasWeak) {
                base = PlannerGranularity.FINE;
                profile = "内部 LLM 兜底（API_KEY_LLM / WEB_BROWSER）";
            } else if (hasStrong && hasWeak) {
                base = PlannerGranularity.FINE;
                profile = "混合（外部 + 内部兜底）";
            } else {
                // 白名单非空但画像缺失（agent 已删除 / 异常）：保守不默认细拆
                base = PlannerGranularity.STANDARD;
                profile = "不限定（白名单 Agent 画像缺失）";
            }
        }

        PlannerGranularity granularity = difficulty == TaskAgentPolicy.Difficulty.HIGH
                ? upshift(base) : base;
        return new GranularityDecision(granularity, profile);
    }

    /** difficulty=HIGH 上移一档；COARSE→STANDARD→FINE，FINE 已达上限不变。 */
    private static PlannerGranularity upshift(PlannerGranularity base) {
        return switch (base) {
            case COARSE -> PlannerGranularity.STANDARD;
            case STANDARD -> PlannerGranularity.FINE;
            case FINE -> PlannerGranularity.FINE;
        };
    }
}