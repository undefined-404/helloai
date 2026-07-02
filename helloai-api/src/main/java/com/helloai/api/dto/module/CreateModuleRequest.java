package com.helloai.api.dto.module;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateModuleRequest {
    @NotBlank(message = "模块名称不能为空")
    private String name;
    private String description;
}
