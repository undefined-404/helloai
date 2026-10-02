package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Executor 执行回填记录（Phase B 目标态）。
 *
 * <p>对应 {@code task_execution_record} 表，每次执行结束由
 * {@code ExecutionResultHandler} 写入一条；rework 时按
 * {@code (task_id, sub_task_id)} 联合唯一约束 UPSERT 覆盖旧记录。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "task_execution_record", autoResultMap = true)
public class TaskExecutionRecordEntity extends BaseEntity {

    /** 主任务 ID（无外键 CASCADE，靠应用层协调）。 */
    private Long taskId;

    /** 子任务 ID（与 taskId 联合唯一）。 */
    private Long subTaskId;

    /** 执行 Agent ID（可空，例如 LLM-as-Executor 暂未绑定工牌）。 */
    private Long agentId;

    /** 子任务标题（冗余存储，便于查询展示）。 */
    private String title;

    /** 产出摘要（EXECUTION_RECORD 协议的 SUMMARY 字段）。 */
    private String summary;

    /**
     * 关键决策（JSONB 字符串数组）：EXECUTION_RECORD 协议的 KEY_DECISIONS 字段。
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> keyDecisions;

    /**
     * 下游须知（JSONB 字符串数组）：EXECUTION_RECORD 协议的 DOWNSTREAM_NOTES 字段。
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> downstreamNotes;

    /**
     * 产出文件列表（JSONB 字符串数组）：EXECUTION_RECORD 协议的 DELIVERABLES 字段。
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> deliverables;
}