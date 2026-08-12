package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.Map;

@Data
public class AgentCreateRequest {
    private String name;
    private String role;
    private String modelType;
    private Map<String, Object> modelConfig;
    private String remark;
}
