package com.helloai.api.dto.task;

import lombok.Data;

/**
 * 任务关联数据统计（删除前风险提示 + 删除结果回显共用）。
 */
@Data
public class TaskRelatedCounts {
    private Long taskId;
    private String taskTitle;
    /** 子任务总数（含死信行） */
    private Integer subTaskCount;
    /** 处于 ASSIGNED/IN_PROGRESS 的子任务数（删除会丢弃其在途执行结果） */
    private Integer activeSubTaskCount;
    /** DEAD_LETTER 死信子任务数 */
    private Integer deadLetterCount;
    private Integer moduleCount;
    private Integer reviewCount;
    private Integer executionCount;
    /** 引用本任务及其子任务/审查记录的未读收件箱消息数 */
    private Integer unreadInboxCount;
    private Integer timelineCount;
}
