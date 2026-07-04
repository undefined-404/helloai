package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
public class AgentResponse {
    private Long id;
    private String name;
    private AgentRole role;
    private String apiKey;
    private String modelType;
    private Map<String, Object> modelConfig;
    private String specializationSlug;
    private AgentStatus status;
    private Integer score;
    private String remark;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
