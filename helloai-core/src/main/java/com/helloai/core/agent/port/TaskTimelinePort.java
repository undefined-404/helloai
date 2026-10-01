package com.helloai.core.agent.port;

import com.helloai.common.constant.AgentRole;

import java.util.Map;

/**
 * 任务时间线记录端口 —— agent 侧（消费方）定义的窄契约。
 *
 * <p><b>为什么需要它</b>：agent 域多处需要把执行轨迹写入 task 域的任务时间线，
 * 此前直接依赖 {@code com.helloai.core.task.service.TaskTimelineService}，
 * 构成 CODE_STYLE §6 反向依赖（链路 {@code planner > review > task > agent > system > shared}，
 * agent 低于 task）。改为依赖本端口后，agent 侧不再 import task 域类型。</p>
 *
 * <p><b>归属判据（CODE_STYLE §7.2）</b>：消费方 {@code agent} <b>低于</b>提供方 {@code task}，
 * 故端口落消费方 {@code agent.port}，实现落提供方 {@code task} 侧
 * （{@code TaskTimelinePortAdapter}），实现侧依赖 {@code task → agent} 属 <b>顺向合法</b>。</p>
 *
 * <p><b>为什么是端口而非领域事件</b>（2026-10-01 决策）：本端口形参全为基础类型与 {@link Map}，
 * 不存在实体泄漏；改造后调用语义与原 {@code TaskTimelineService.recordEvent} <b>逐字相同</b>
 * （同事务同步写、append-only 审计），<b>零语义变更</b>。领域事件方案会把「同事务写」改为
 * 「提交后 best-effort 写」，属语义变更且需端到端回归，故本批不采用。</p>
 *
 * <p><b>只覆盖 agent 实际使用的方法</b>：{@code TaskTimelineService} 的查询方法
 * （{@code listBySubTaskId} 等）由 api 层直接使用，不经本端口。</p>
 */
public interface TaskTimelinePort {

    /**
     * 记录一条任务事件。
     *
     * @param taskId    主任务 ID（可空，用于系统级事件）
     * @param subTaskId 子任务 ID（可空）
     * @param eventType 事件类型（snake_case）
     * @param role      事件产生方角色
     * @param agentId   关联 Agent ID（可空）
     * @param payload   事件负载（可空）
     */
    void recordEvent(Long taskId,
                     Long subTaskId,
                     String eventType,
                     AgentRole role,
                     Long agentId,
                     Map<String, Object> payload);
}
