package com.helloai.api.dto.event;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 执行恢复点（Recovery 消费面读侧暴露）。
 *
 * <p>用于 GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId} 返回结构，字段对齐
 * core 投影 {@code RecoveryPoint}。事件流主键序列化为字符串，避免前端 JS Number 精度丢失
 * （LongId 规范，同 {@link AgentEventItem}）。</p>
 *
 * <p>语义：本结构是「上一次执行尝试停在哪」的<b>只读推定</b>，来源为 append-only 的
 * {@code agent_event}；它<b>不代表</b>业务状态，也不得被消费方用于直接改写业务表。</p>
 */
@Data
public class RecoveryPointItem {

    /** 事件流是否显示「上一轮执行未闭合」（有 agent_started 且其后无 agent_completed） */
    private boolean unfinished;

    /** 未闭合轮次序号；unfinished=false 时为空 */
    private Integer unfinishedTurn;

    /** 中断点工具名（已开始未完成）；无悬挂调用时为空 */
    private String danglingTool;

    /** 未闭合轮次内已完成的工具名（按事件序保序去重） */
    private List<String> completedTools;

    /** 事件流最后一条事件的类型；轨迹为空时为空 */
    private String lastEventType;

    /** 事件流最后一条事件的主键（即 append-only 顺序位）；轨迹为空时为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastEventSequence;

    /** 事件流最后一条事件的写入时刻；轨迹为空时为空 */
    private OffsetDateTime lastEventAt;

    /** 事件流是否已出现终态事件（agent_completed / run_completed） */
    private boolean terminalEventSeen;
}
