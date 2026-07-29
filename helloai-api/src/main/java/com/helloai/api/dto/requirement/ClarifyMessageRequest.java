package com.helloai.api.dto.requirement;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClarifyMessageRequest {
    @NotBlank(message = "消息内容不能为空")
    private String message;
}
