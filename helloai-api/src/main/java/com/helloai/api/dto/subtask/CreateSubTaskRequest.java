package com.helloai.api.dto.subtask;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSubTaskRequest {
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
    private Long moduleId;
    @NotBlank(message = "子任务名称不能为空")
    private String title;
    private String description;
    private String deliverable;
    private String acceptance;
    private String priority;
    private Long assignedAgent;
}
