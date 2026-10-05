package com.helloai.core.agent.port;

import com.helloai.common.constant.AgentRole;

/**
 * Team 成员只读快照（agent 域自有的对外数据契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 task 域（依赖链下标 2）【高于】
 * 提供方 agent 域（下标 3），故读契约落【提供方】{@code agent.port}；
 * 消费方 {@code task → agent.port} 为顺向合法，从此不再 import {@code agent.entity.TeamMember}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>：{@code id} / {@code agentId} /
 * {@code slotRole} / {@code weight}（task 域 {@code TeamPolicyExpander} 槽位展开实测并集）。</p>
 */
public record TeamMemberView(
        Long id,
        Long agentId,
        AgentRole slotRole,
        Integer weight
) {
}
