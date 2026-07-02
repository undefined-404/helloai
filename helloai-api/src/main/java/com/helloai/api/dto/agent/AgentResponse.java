package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AgentResponse {
    private Long id;
    private String name;
    private AgentRole role;
    private String apiKey;
    private String modelType;
    private AgentStatus status;
    private Integer score;
    private String remark;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
