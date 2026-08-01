package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task")
public class Task extends BaseEntity {

    private String title;
    private String description;
    private TaskStatus status;

    /** 最终整合报告正文（V32，Markdown；null=尚未生成），由 Planner 收口后整合全部子任务产出。 */
    private String finalReport;

    /** 生成报告的 Planner Agent ID（V32，软引用无 FK）。 */
    private Long finalReportAgentId;

    /** 报告生成时间（V32）。 */
    private OffsetDateTime finalReportTime;

    /**
     * 任务扩展上下文（V35 新增 JSONB）。
     *
     * <p>当前用途：Task Running Spec（Phase A JSONB 过渡态）——
     * {@code runningSpec.baseline} / {@code runningSpec.executionRecords} /
     * {@code runningSpec.contextSummary}。</p>
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> context;
}
