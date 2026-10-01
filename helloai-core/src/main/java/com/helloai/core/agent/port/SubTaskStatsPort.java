package com.helloai.core.agent.port;

import java.util.Map;

/**
 * 子任务统计只读端口（agent 域消费，task 域实现）。
 *
 * <p><b>端口归属判据</b>（{@code doc/HelloAI_CODE_STYLE.md} §7.2）：消费方是 <b>agent</b>、
 * 提供方是 <b>task</b>；按依赖链 {@code planner > review > task > agent > system > shared}，
 * agent <b>低于</b> task ⇒ 属「消费方低于提供方」情形 ⇒ 端口落<b>消费方 {@code agent.port}</b>、
 * 适配器落提供方 task 域，实现侧依赖 {@code task → agent} 属<b>顺向合法</b>。</p>
 *
 * <p><b>为什么是「统计」而不是「取实体」</b>：本端口的消费方只关心<em>计数</em>与<em>是否存在</em>，
 * 不读 {@code SubTask} 任何字段。因此端口只返回基本类型 ——
 * 若返回 {@code task.entity.SubTask}，消费方仍要 import task 实体，
 * {@code agent → task} 计数<b>不会下降</b>（只是把 service import 换成 entity import）。
 * 需要读取实体字段的场景属于「快照值对象」范畴，另行处理。</p>
 *
 * <p>实现见 {@code task.service.impl.SubTaskStatsPortAdapter}（薄委托 {@code SubTaskService}，
 * 不改动任何 SQL 与语义）。</p>
 */
public interface SubTaskStatsPort {

    /**
     * Agent 工作量统计（assigned / inProgress / done / blocked / review 等分状态计数）。
     *
     * @param agentId Agent ID
     * @return 状态名 → 计数；绝不返回 null
     */
    Map<String, Integer> countByStatusForAgent(Long agentId);

    /**
     * 指派给该 Agent 的子任务总数。
     */
    long countByAssignedAgent(Long agentId);

    /**
     * 该 Agent 作为评审人的评审总数。
     */
    long countReviewByReviewerAgent(Long agentId);

    /**
     * 该 Agent 当前在跑（in-flight）的子任务数。
     */
    int countInFlightByAgent(Long agentId);

    /**
     * 该 Agent 是否存在在跑（in-flight）的子任务。
     *
     * <p>由 {@code SubTaskService.selectInFlightByAgent(agentId, 1)} 的空判定收口而来
     * （原调用方在 agent 域自持 {@code task.entity.SubTask} 仅为判空）——
     * 语义等价，且不再向 agent 域泄漏 task 实体。</p>
     */
    boolean existsInFlight(Long agentId);
}
