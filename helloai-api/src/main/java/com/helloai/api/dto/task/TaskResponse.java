package com.helloai.api.dto.task;

import com.helloai.common.constant.TaskStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class TaskResponse {
    private Long id;
    private String title;
    private String description;
    private TaskStatus status;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
