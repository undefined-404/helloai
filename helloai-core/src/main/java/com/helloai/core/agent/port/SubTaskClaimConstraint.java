package com.helloai.core.agent.port;

import java.util.List;

/**
 * 子任务认领约束（纯数据，端口契约）——「认领前准入」所需的白名单 + 必需技能。
 *
 * <p><b>归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：消费方是 <b>agent</b>、
 * 提供方是 <b>task</b>；按依赖链 {@code planner > review > task > agent > system > shared}，
 * agent <b>低于</b> task ⇒ 属「消费方低于提供方」情形 ⇒ 契约（本 record）落<b>消费方
 * {@code agent.port}</b>、适配器（{@code SubTaskQueryPortAdapter}）落提供方 task 域，
 * 实现侧依赖 {@code task → agent.port} 属<b>顺向合法</b>。</p>
 *
 * <p><b>为什么用纯数据 record 而不是直接传 task 实体 / policy Map</b>：白名单口径
 * （{@code agent_policy.executorAgentIds}）与技能口径（子任务级 ∪ 任务级 {@code required_skills}）
 * 的单源都在 task 域；若把 policy Map 或实体透传给 agent 域，消费方就得自行解析规则 ⇒
 * 规则复制、入口间漂移（历史教训）。故此处只承载<b>已解析好的结果</b>，匹配动作仍在 agent 域
 * 用既有 {@code AgentSelector.AgentSelectionConstraints} 完成，口径零复制。</p>
 *
 * @param allowedAgentIds 执行者白名单；null/空 = 不限定
 * @param requiredSkills  任务要求技能（子任务级 ∪ 任务级，去重保序）；null/空 = 不限定
 */
public record SubTaskClaimConstraint(List<Long> allowedAgentIds, List<String> requiredSkills) {

    /**
     * 构建约束；白名单与技能<b>均空时返回 {@code null}</b>（语义「不约束」，与历史行为一致）。
     *
     * <p>与 {@code TaskDispatchPort.DispatchConstraints.of} 逐字同款的空判口径：
     * 消费方拿到 {@code null} 即放行，不做任何判定。</p>
     */
    public static SubTaskClaimConstraint of(List<Long> allowedAgentIds, List<String> requiredSkills) {
        boolean noIds = allowedAgentIds == null || allowedAgentIds.isEmpty();
        boolean noSkills = requiredSkills == null || requiredSkills.isEmpty();
        return (noIds && noSkills) ? null : new SubTaskClaimConstraint(allowedAgentIds, requiredSkills);
    }
}