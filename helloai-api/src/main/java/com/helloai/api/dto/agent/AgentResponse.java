package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class AgentResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String name;
    private AgentRole role;
    private String apiKey;
    private String modelType;
    private Map<String, Object> modelConfig;
    private AgentStatus status;
    private Integer score;
    private String remark;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;

    // 补全 + 三件套（list/getById 返回）
    private AgentAccessType accessType;
    private Map<String, Object> capabilities;
    private Map<String, Object> labels;
    private List<String> skills;
    private OffsetDateTime lastSeenAt;
    private OffsetDateTime lastActiveAt;
    private AgentOnlineStatus onlineStatus;
    private String offlineReason;
    private OffsetDateTime offlineAt;
}
