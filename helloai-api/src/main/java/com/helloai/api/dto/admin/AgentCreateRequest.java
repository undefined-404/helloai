package com.helloai.api.dto.admin;

import lombok.Data;

@Data
public class AgentCreateRequest {
    private String name;
    private String role;
    private String modelType;
    private String remark;
}
