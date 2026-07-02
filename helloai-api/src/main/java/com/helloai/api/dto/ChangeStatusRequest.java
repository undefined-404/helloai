package com.helloai.api.dto;

import lombok.Data;

@Data
public class ChangeStatusRequest {
    private Long subTaskId;
    private String newStatus;
    private Long agentId;
}
