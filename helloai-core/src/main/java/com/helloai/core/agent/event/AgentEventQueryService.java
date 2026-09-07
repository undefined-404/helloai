package com.helloai.core.agent.event;

import java.util.List;

/**
 * Agent 事件流读侧查询（Phase 0 A6）。
 *
 * <p>职责：从 append-only 的 {@code agent_event} 重建执行轨迹，是 Timeline / Replay / Audit
 * 共用的首个读消费面。仅读、不参与业务状态决策（事件 write-only 纪律的读侧对偶）。</p>
 */
public interface AgentEventQueryService {

    /**
     * 按子任务读取有序执行轨迹。
     *
     * @param subTaskId 子任务 ID；为空时返回空列表
     * @return 按 {@code createTime ASC, id ASC} 有序的轨迹投影，永不为 null
     */
    List<AgentEventTraceItem> traceBySubTaskId(Long subTaskId);
}