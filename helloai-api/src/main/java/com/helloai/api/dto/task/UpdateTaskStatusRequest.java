package com.helloai.api.dto.task;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTaskStatusRequest {
    @NotBlank(message = "状态不能为空")
    private String status;
}
