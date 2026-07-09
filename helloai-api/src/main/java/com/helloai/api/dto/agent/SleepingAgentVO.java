package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * SLEEPING Agent 列表 VO（v2.4 §4.3 批次 3）。
 *
 * <p>字段精简：只暴露列表视图需要的关键信息（id/name/role/onlineStatus/updateBy/updateTime），
 * 避免把全量 agent 字段（包括 api_key）暴露到管理列表接口。
 */
@Data
public class SleepingAgentVO {
    private Long id;
    private String name;
    private AgentRole role;
    private AgentOnlineStatus onlineStatus;
    /** 最近一次管理员操作人 */
    private String updateBy;
    /** 最近一次状态变更时间 */
    private OffsetDateTime updateTime;
}