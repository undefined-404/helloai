package com.helloai.api.dto.prompt;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 输入优化请求（Planner Chat「优化输入」）。
 */
@Data
public class PromptEnhanceRequest {

    /** 用户当前输入（待优化的原始内容）。 */
    @NotBlank(message = "待优化的输入内容不能为空")
    private String prompt;
}
