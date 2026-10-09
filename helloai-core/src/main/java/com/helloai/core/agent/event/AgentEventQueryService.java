package com.helloai.core.agent.event;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * Agent 事件流读侧查询。
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
     * 按 Run 读取完整执行轨迹（Replay 读侧）。
     *
     * <p>一个 Run（{@code run-{taskId}-{roundNum}}，见 ADR-001）跨 Turn / Step
     * 全量重建执行轨迹，支撑 G-001 验收「一个 Run 可以按 sequence 重建轨迹」；
     * Replay 仅读取历史，不代表再次产生副作用（doc/design/Agent_Event_Stream.md 原则 6）。</p>
     *
     * @param runId Run 标识；为空或空白时返回空列表
     * @return 按 {@code createTime ASC, id ASC} 有序的轨迹投影，永不为 null
     */
    List<AgentEventTraceItem> traceByRunId(String runId);

    /**
     * 按 Task 读取 Run 级完整轨迹（G-006 消费面易用性：任务维度入口，免传 runId）。
     *
     * <p>内部按 ADR-001 §3.1 标识规则由 taskId 推导 runId（{@code AgentEventContextResolver.resolveRunId}），
     * 语义等价 {@link #traceByRunId(String)}；runId 生成规则今后升级（如 planInstanceId）时
     * 仅本方法受影响，调用方不感知规则。</p>
     *
     * @param taskId Task ID；为空时返回空列表
     * @return 按 {@code createTime ASC, id ASC} 有序的轨迹投影，永不为 null
     */
    List<AgentEventTraceItem> traceByTaskId(Long taskId);

    /**
     * 按 Task 分页读取事件审计列表（Audit 读侧）。
     *
     * <p>按 task 维度查询执行事实（谁在何时做了什么），支持可选 {@code eventType} 与
     * {@code timeStart}/{@code timeEnd} 时间范围过滤。时间字符串由实现负责按以下两种
     * 形态之一解析（与前端 {@code el-date-picker} 默认输出对齐）：</p>
     *
     * <ul>
     *   <li>{@code yyyy-MM-dd HH:mm:ss}（前端显式 {@code value-format} 形态，无时区）</li>
     *   <li>{@code yyyy-MM-ddTHH:mm:ss[.fff][±HH:mm]}（前端默认 ISO 形态，带时区偏移）</li>
     * </ul>
     *
     * <p>解析后必须以 {@link java.time.OffsetDateTime} 形式下传至 mapper，避免
     * {@code String → timestamptz} 在 PostgreSQL 上抛
     * {@code operator does not exist: timestamp with time zone >= character varying}
     * （实测：http-nio 500）。</p>
     *
     * @param taskId    Task ID；为空时返回空分页
     * @param eventType 事件类型过滤（可空/空白 = 不过滤）
     * @param timeStart 时间下界字符串（{@code null} / 空白 = 不限；包含边界）
     * @param timeEnd   时间上界字符串（{@code null} / 空白 = 不限；包含边界）
     * @param pageNum   页码（从 1 起，由调用方校验）
     * @param pageSize  页大小（由调用方校验）
     * @return 分页轨迹投影（含 total / pages 元数据），永不为 null
     */
    IPage<AgentEventTraceItem> pageAuditByTaskId(Long taskId, String eventType, String timeStart, String timeEnd,
                                                 long pageNum, long pageSize);
}