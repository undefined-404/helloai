package com.helloai.common.constant;

public enum SubTaskStatus {
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
