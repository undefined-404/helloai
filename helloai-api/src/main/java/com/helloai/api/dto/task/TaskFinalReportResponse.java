package com.helloai.api.dto.task;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 任务最终整合报告（V32，读取 task.final_report 专列组装）。
 */
@Data
public class TaskFinalReportResponse {
    private Long taskId;
    /** 报告正文 Markdown；null 表示尚未生成 */
    private String content;
    /** 生成报告的 Planner Agent（软引用，可能已删除） */
    private Long agentId;
    private String agentName;
    private OffsetDateTime generatedAt;
}
