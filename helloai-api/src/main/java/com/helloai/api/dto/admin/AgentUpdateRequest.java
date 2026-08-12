package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentUpdateRequest {
    private String name;
    private String modelType;
    private Map<String, Object> modelConfig;
    private String remark;
    /** 能力声明列表（A2）：显式传入整体替换；null 保持现状 */
    private List<String> skills;
}
