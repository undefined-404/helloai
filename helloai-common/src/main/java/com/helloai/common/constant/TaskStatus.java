package com.helloai.common.constant;

public enum TaskStatus {
    PENDING,
    /** 规划中：Planner 拆解进行中或草案待确认，防重复触发拆解 */
    PLANNING,
    IN_PROGRESS,
    DONE,
    CANCELLED
}
