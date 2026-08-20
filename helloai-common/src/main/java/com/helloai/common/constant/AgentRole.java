package com.helloai.common.constant;

public enum AgentRole {
    PLANNER,
    EXECUTOR,
    REVIEWER,
    /** 系统角色：管理员操作 / 系统级事件专用，不用于业务 Agent 实体 */
    SYSTEM
}
