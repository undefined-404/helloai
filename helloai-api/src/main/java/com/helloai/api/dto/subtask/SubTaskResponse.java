package com.helloai.api.dto.subtask;

import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

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
    /** 依赖的子任务 id 列表（V27 新增，同 Task 内；空列表=无依赖） */
    private List<Long> dependsOn;
    private OffsetDateTime deadline;
    private OffsetDateTime completedAt;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
