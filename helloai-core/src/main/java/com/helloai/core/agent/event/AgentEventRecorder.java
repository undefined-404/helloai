package com.helloai.core.agent.event;

import com.helloai.common.constant.AgentEventType;

import java.util.Map;

/**
 * Agent 事件记录器（Phase 0 B1，ADR-001 Run/Turn/Step 模型配套）。
 *
 * <p>同事务双写：
 * <ol>
 *   <li>{@code agent_event}（append-only 事件轨迹，Event Stream 主表）</li>
 *   <li>{@code agent_outbox_event}（Outbox，由 {@code AgentEventCompensationTask} 投递 MQ）</li>
 * </ol>
 * 两表共享同一 {@code eventId}（B3 对账键）；事件仅 write-only，不参与任何业务决策（B2 埋点纪律）。</p>
 */
public interface AgentEventRecorder {

    /**
     * 记录一次事件。
     *
     * <p>本方法自带事务：与调用方事务合并（同线程传播），任一遍写失败整体回滚。
     * 埋点调用方（B2）应按「事件 write-only」纪律自行降级（try-catch 不阻断主链路）。</p>
     *
     * @param runId     Run 标识（run-{taskId}-{roundNum}，ADR-001；不可空）
     * @param taskId    主任务 ID（可空：非任务级事件）
     * @param subTaskId 子任务 ID（可空：Run/Task 级事件）
     * @param turn      Turn 序号（一次 Agent 完整工作周期，从 1 起）
     * @param step      Turn 内原子动作序号（从 0 起；0 表示非 Step 级事件）
     * @param eventType 事件类型（不可空）
     * @param agentId   关联 Agent ID（可空）
     * @param payload   事件负载（可空，空时存空 Map）
     */
    void record(String runId, Long taskId, Long subTaskId, int turn, int step,
                AgentEventType eventType, Long agentId, Map<String, Object> payload);
}