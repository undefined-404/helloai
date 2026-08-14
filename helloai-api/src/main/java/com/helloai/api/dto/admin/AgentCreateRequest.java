package com.helloai.api.dto.admin;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentCreateRequest {
    private String name;
    private String role;
    private String modelType;
    private Map<String, Object> modelConfig;
    private String remark;

    /** 技能标签列表（V52）：按模型能力驱动落库，不传时按 accessType + 模型能力推导。 */
    private List<String> skills;
}
