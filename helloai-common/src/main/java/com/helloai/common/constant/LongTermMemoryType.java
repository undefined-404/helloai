package com.helloai.common.constant;

/**
 * 长期记忆类型（N-009，C5-S1）。
 *
 * <p>记忆平面五类边界（C5 设计 §0.3）：SESSION=单次需求会话摘要 /
 * TASK=任务内运行态摘要 / USER=用户级偏好与历史意图。</p>
 */
public enum LongTermMemoryType {
    /** 单次需求澄清会话摘要（finalize 归档）。 */
    SESSION,
    /** 任务内运行态摘要（task_running_spec.contextSummary 范式）。 */
    TASK,
    /** 用户级偏好/历史意图（挂用户维度）。 */
    USER
}
