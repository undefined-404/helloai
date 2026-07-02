package com.helloai.api.dto.task;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTaskRequest {
    @NotBlank(message = "任务名称不能为空")
    private String title;
    private String description;
}
