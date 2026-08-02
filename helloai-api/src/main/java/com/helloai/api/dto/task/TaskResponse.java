package com.helloai.api.dto.task;

import com.helloai.common.constant.TaskStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class TaskResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String title;
    private String description;
    private TaskStatus status;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
