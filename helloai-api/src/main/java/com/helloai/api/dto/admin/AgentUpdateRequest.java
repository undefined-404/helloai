package com.helloai.api.dto.admin;

import lombok.Data;

@Data
public class AgentUpdateRequest {
    private String name;
    private String modelType;
    private String remark;
}
