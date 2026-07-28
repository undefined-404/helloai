package com.helloai.api.dto.subtask;

import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class SubTaskResponse {
    private Long id;
    private Long taskId;
    /** 主任务标题冗余字段（列表/详情展示归属任务用，由 Controller 批量回填） */
    private String taskTitle;
    private Long moduleId;
    private String title;
    private String deliverable;
    private String acceptance;
    private String priority;
    private SubTaskStatus status;
    private Long assignedAgent;
    private String content;
    private Integer reworkCount;
    private OffsetDateTime deadline;
    private OffsetDateTime completedAt;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
