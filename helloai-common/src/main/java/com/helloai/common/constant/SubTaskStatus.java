package com.helloai.common.constant;

public enum SubTaskStatus {
    /** 规划草案：Planner 拆解生成、等待用户确认；不参与 claim/分发/超时回收/统计 */
    PENDING_PLAN_REVIEW,
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    PAUSED,
    REVIEW,
    DONE,
    REWORK,
    BLOCKED,
    CANCELLED,
    /** 死信：重分配熔断后转入人工兜底池（可人工再指派或放弃，非终态） */
    DEAD_LETTER
}
