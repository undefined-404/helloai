package com.helloai.api.dto.task;

import com.helloai.common.constant.FinalReportStatus;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 任务最终整合报告（读取 task.final_report 专列组装；含生成状态）。
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
    /** 报告生成状态（NONE/GENERATING/DONE/FAILED），前端据此禁用按钮与展示中间态 */
    private FinalReportStatus status;

    /** §12.1 上一版槽：true=存在可恢复的上一版（final_report_prev 非空），前端据此显示「恢复上一版」按钮 */
    private boolean hasPrev;

    /** 上一版报告生成时间（final_report_prev_time；无上一版为 null） */
    private OffsetDateTime prevGeneratedAt;
}
