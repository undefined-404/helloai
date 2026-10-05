package com.helloai.core.agent.port;

/**
 * Task-Team 成员清理端口（agent 域消费，task 域实现）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：消费方 {@code agent}
 * <b>低于</b>提供方 {@code task} ⇒ 端口落消费方 {@code agent.port}、适配器落提供方
 * {@code task.adapter}；实现侧 {@code task → agent} 的 import 属<b>顺向合法</b>。</p>
 *
 * <p><b>存在理由</b>：{@code task_agent_member} 是 task 域表（V98），其 {@code agent_id}
 * 上有外键 {@code task_agent_member_agent_id_fkey} 指向 {@code agent.id}。Agent 级联删除
 * （{@code AgentServiceImpl#deleteAgentCascade}，agent 域）必须在删除 agent 本体前清空这些成员行，
 * 否则撞外键 → HTTP 500（P2-4，2026-10-05）。agent 域不得直捅 task 域 Mapper，
 * 故收口为本端口。</p>
 *
 * <p>实现见 {@code task.adapter.TaskTeamMemberPortAdapter}。</p>
 */
public interface TaskTeamMemberPort {

    /**
     * 物理删除某 Agent 在 {@code task_agent_member} 的全部成员行。
     *
     * <p>调用方（agent 域级联删除）保持自身事务，提供方以 REQUIRED 传播加入，属同事务同步写
     * —— 不能用事件替代（删除必须先于 agent 本体，事件无法保证顺序）。</p>
     *
     * @param agentId Agent ID；{@code null} 时提供方应返回 0（防御）
     * @return 实际删除行数
     */
    int physicalDeleteByAgentId(Long agentId);
}
