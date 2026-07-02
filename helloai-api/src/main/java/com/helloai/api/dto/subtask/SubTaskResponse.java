package com.helloai.api.dto.subtask;

import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class SubTaskResponse {
    private Long id;
    private Long taskId;
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
