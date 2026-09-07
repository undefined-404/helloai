package com.helloai.core.agent.event;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * Agent 事件流读侧查询（Phase 0 A6 / A7）。
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

    /**
     * 按 Run 读取完整执行轨迹（Phase 0 A7 Replay 读侧）。
     *
     * <p>一个 Run（{@code run-{taskId}-{roundNum}}，见 ADR-001）跨 Turn / Step
     * 全量重建执行轨迹，支撑 G-001 验收「一个 Run 可以按 sequence 重建轨迹」；
     * Replay 仅读取历史，不代表再次产生副作用（design/Agent_Event_Stream.md 原则 6）。</p>
     *
     * @param runId Run 标识；为空或空白时返回空列表
     * @return 按 {@code createTime ASC, id ASC} 有序的轨迹投影，永不为 null
     */
    List<AgentEventTraceItem> traceByRunId(String runId);

    /**
     * 按 Task 分页读取事件审计列表（Phase 0 A7 Audit 读侧）。
     *
     * <p>按 task 维度查询执行事实（谁在何时做了什么），支持可选 {@code eventType}
     * 过滤，最新在前（{@code createTime DESC, id DESC}）；供 Audit 消费面从
     * Event Stream 获取事实（G-001 验收）。</p>
     *
     * @param taskId    Task ID；为空时返回空分页
     * @param eventType 事件类型过滤（可空/空白 = 不过滤）
     * @param pageNum   页码（从 1 起，由调用方校验）
     * @param pageSize  页大小（由调用方校验）
     * @return 分页轨迹投影（含 total / pages 元数据），永不为 null
     */
    IPage<AgentEventTraceItem> pageAuditByTaskId(Long taskId, String eventType, long pageNum, long pageSize);
}