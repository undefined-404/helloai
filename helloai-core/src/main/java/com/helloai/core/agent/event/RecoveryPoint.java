package com.helloai.core.agent.event;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 执行恢复点投影（Recovery 消费面，只读）。
 *
 * <p>由 {@link AgentEventQueryService#projectRecoveryPoint(Long)} 从 append-only 的
 * {@code agent_event} 推定「上一次执行尝试停在哪」，是 Timeline / Replay / Audit 之后的
 * 第四个事件流消费面。<b>纯读</b>：不落库、不改业务状态、不反向修正任何东西
 * （严守 {@code design/Agent_Event_Stream.md} 原则 2「Event 是 execution fact，不是业务状态」）。</p>
 *
 * <h3>为什么需要它（与 {@code agent_session} 表的分工）</h3>
 * <p>续跑上下文此前只取自 {@code agent_session} 表（{@code AgentSessionService#findLatestInterrupted}）。
 * 该表是<b>可变业务表</b>：新尝试会写入 ACTIVE 行、租约回收会 {@code abortActiveBySubTaskId}；
 * 查询口径是「只看最新一行，ACTIVE / COMPLETED 一律零注入」。而事件流是
 * <b>append-only 事实</b>——进程被中断时留下的「{@code TOOL_CALL_STARTED} 之后没有
 * {@code TOOL_CALL_COMPLETED}」是永不被覆盖的中断点证据。二者互补：会话表记录
 * 「被回收的中断」（含 snapshot），事件流记录「<b>所有</b>未闭合的执行」，后者是兜底且更可靠的来源。</p>
 *
 * <h3>判定口径（零阈值，全部由结构量推出）</h3>
 * <ul>
 *   <li>{@code unfinished}：按事件序扫描，「{@code AGENT_STARTED} 置开、{@code AGENT_COMPLETED} 置闭」，
 *       扫描结束后仍处于「开」⇒ 该轮未闭合。事件层不定义失败终态事件（ADR-001 §5.3）
 *       ⇒ 「无 {@code AGENT_COMPLETED}」是唯一可用的未终结信号。</li>
 *   <li>{@code danglingTool}：未闭合轮次内「有 {@code TOOL_CALL_STARTED}、无配对
 *       {@code TOOL_CALL_COMPLETED}」的工具名（payload {@code tool}）——即中断点本身。</li>
 *   <li>{@code completedTools}：未闭合轮次内已拿到 {@code TOOL_CALL_COMPLETED} 的工具名，
 *       按事件序保序去重（接续者据此避免重复副作用）。</li>
 * </ul>
 *
 * @see AgentEventQueryService#projectRecoveryPoint(Long)
 * @see AgentEventTraceItem
 */
@Value
@Builder
public class RecoveryPoint {

    /** 事件流是否显示「上一轮执行未闭合」（有 {@code AGENT_STARTED} 且其后无 {@code AGENT_COMPLETED}）。 */
    boolean unfinished;

    /** 未闭合轮次序号；{@code unfinished=false} 时为 {@code null}。 */
    Integer unfinishedTurn;

    /** 中断点工具名（已开始未完成）；无悬挂调用时为 {@code null}。 */
    String danglingTool;

    /** 未闭合轮次内已完成的工具名（按事件序保序去重）；永不为 {@code null}。 */
    List<String> completedTools;

    /** 事件流最后一条事件的类型；轨迹为空时为 {@code null}。 */
    String lastEventType;

    /** 事件流最后一条事件的主键（即 append-only 顺序位）；轨迹为空时为 {@code null}。 */
    Long lastEventSequence;

    /** 事件流最后一条事件的写入时刻；轨迹为空时为 {@code null}。 */
    OffsetDateTime lastEventAt;

    /** 事件流是否已出现终态事件（{@code AGENT_COMPLETED} / {@code RUN_COMPLETED}）。 */
    boolean terminalEventSeen;

    /** 事件流无任何事件时的空投影（{@code unfinished=false}，各字段为 null / 空表）。 */
    public static RecoveryPoint none() {
        return RecoveryPoint.builder()
                .unfinished(false)
                .completedTools(List.of())
                .terminalEventSeen(false)
                .build();
    }
}
