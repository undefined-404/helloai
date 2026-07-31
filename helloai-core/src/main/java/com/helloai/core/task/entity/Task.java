package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

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
}
