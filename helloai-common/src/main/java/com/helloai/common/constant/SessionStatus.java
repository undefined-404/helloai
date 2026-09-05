package com.helloai.common.constant;

/**
 * Agent 执行会话状态（Phase 1 Step 3，agent_session.status）。
 *
 * <p>会话 = 一次执行尝试（ADR-001 Turn 级）的快照/中断点/恢复上下文载体，
 * 与 {@code agent_execution_record}（命令生命周期）互补。</p>
 *
 * <ul>
 *   <li>{@link #ACTIVE}：执行中（上下文装配完成 / LLM 在飞）</li>
 *   <li>{@link #COMPLETED}：执行成功（结果已回写）</li>
 *   <li>{@link #FAILED}：执行失败（结果回写失败路径）</li>
 *   <li>{@link #TIMEOUT}：执行超时（补偿路径预留）</li>
 *   <li>{@link #ABORTED}：被回收/重派中断（幂等防重入）</li>
 * </ul>
 */
public enum SessionStatus {

    ACTIVE,
    COMPLETED,
    FAILED,
    TIMEOUT,
    ABORTED
}
