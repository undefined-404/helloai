package com.helloai.api.dto.subtask;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReassignRequest {
    @NotNull(message = "Agent ID 不能为空")
    private Long agentId;
}
