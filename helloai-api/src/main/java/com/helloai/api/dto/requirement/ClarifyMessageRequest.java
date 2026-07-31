package com.helloai.api.dto.requirement;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClarifyMessageRequest {
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 手动指定的 Planner Agent ID（仅新建会话时生效；空=系统自动选择） */
    private Long plannerAgentId;
}
