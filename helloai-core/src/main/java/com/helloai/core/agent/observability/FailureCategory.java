package com.helloai.core.agent.observability;

/**
 * 外部 Agent 失败类别（Paperclip 阶段一·4：三配额分离）。
 *
 * <p>每类失败归入独立配额，互不挤占 —— 空回复不再挤占真失败配额，
 * 避免「没真正坏掉的 Agent 被提前回退」。</p>
 *
 * <ul>
 *   <li>{@link #RETRY} —— 显式 retry 指令失败（本期无写入源，配额列先就位）；</li>
 *   <li>{@link #CONTINUATION} —— 空回复 / 续跑类失败（{@code EMPTY_REPLY}，可容忍，
 *       不触发真失败的阈值回退）；</li>
 *   <li>{@link #RECOVERY} —— 真失败：离线失联 / 执行超时 / 执行报错（严格配额，
 *       参与阈值回退）。</li>
 * </ul>
 *
 * <p>映射集中在调用方现场（见 {@code ExternalAgentFailureTracker#recordFailure} 的
 * 三调用方）：{@code ExecutionCompensationTask}（超时）/ {@code AgentHealthCheckTask}
 * （失联）→ {@link #RECOVERY}；{@code ExecutionResultHandler}（执行失败）按 output
 * 是否空白归 {@link #CONTINUATION} 或 {@link #RECOVERY}。</p>
 */
public enum FailureCategory {

    /** 显式 retry 指令失败。 */
    RETRY,
    /** 空回复 / 续跑类失败（可容忍，不触发真失败阈值回退）。 */
    CONTINUATION,
    /** 真失败：离线失联 / 执行超时 / 执行报错（严格配额）。 */
    RECOVERY
}
