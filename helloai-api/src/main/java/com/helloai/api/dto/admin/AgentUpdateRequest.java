package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.Map;

@Data
public class AgentUpdateRequest {
    private String name;
    private String modelType;
    private Map<String, Object> modelConfig;
    private String specializationSlug;
    private String remark;
}
