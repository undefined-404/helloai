package com.helloai.core.agent.session.service;

import java.util.Map;

/**
 * Agent 执行会话服务（Phase 1 Step 3，Session Manager / N-007 收口）。
 *
 * <p>职责：维护一次执行尝试（ADR-001 Turn 级）的 {@code agent_session}
 * 快照/中断点/恢复上下文，并暴露恢复消费端入口（{@link #interrupt}）。
 * 全部写入为 <b>best-effort 可观测数据</b>：失败仅告警、绝不阻断执行主链路，
 * 不参与业务决策（与 AgentEventRecorder 事件 write-only 同纪律）。</p>
 *
 * <p>依赖方向：本服务在 agent 域（执行会话归属 agent 域「Agent Execution」），
 * 由 task 域租约回收路径（SubTaskServiceImpl）消费——task → agent 合法（§6）。</p>
 */
public interface AgentSessionService {

    /** 恢复消费端返回的被中断会话摘要（供 timeline 落「执行中断点」）。 */
    record InterruptedSession(Long sessionId, Long agentId, int turn, int step,
                              Map<String, Object> snapshot) {
    }

    /**
     * 执行开始时创建/刷新会话（best-effort 幂等）。
     *
     * <p>同 subTaskId+turn 已存在 ACTIVE 会话（重入）则更新 snapshot/step，
     * 否则插入新会话行。turn 在 reworkFresh/死信重派清零后可能复用，
     * 读取一律取最新（V66 注释）。</p>
     *
     * @param taskId    主任务 ID（派生 run_id）
     * @param subTaskId 子任务 ID
     * @param agentId   执行 Agent ID
     * @param turn      执行尝试序号（ADR-001 Turn）
     * @param step      中断点（上下文装配完成 = 2）
     * @param snapshot  恢复上下文快照（skills/tools/depCount 等装配事实；null 视为空）
     */
    void start(Long taskId, Long subTaskId, Long agentId, int turn, int step, Map<String, Object> snapshot);

    /**
     * LLM 调用完成后推进 step（best-effort；无匹配 ACTIVE 会话则跳过）。
     *
     * @param step 中断点推进到 4（LLM 调用完成）
     */
    void advance(Long subTaskId, Long agentId, int turn, int step);

    /**
     * 执行成功终态：ACTIVE → COMPLETED（best-effort）。
     */
    void complete(Long subTaskId, Long agentId, int turn);

    /**
     * 执行失败终态：ACTIVE → FAILED（error 截断 500 字符，best-effort）。
     */
    void fail(Long subTaskId, Long agentId, int turn, String error);

    /**
     * 恢复消费端：把指定子任务的全部 ACTIVE 会话置 ABORTED（幂等防重入），
     * 并返回被中断的最新会话摘要（无 ACTIVE 会话返回 null）。
     *
     * <p>由租约回收/重派路径调用：先记录中断点（timeline）再 ABORT，
     * 保证「识别未完成执行 → 恢复上下文 → 重新调度」闭环可观测。</p>
     */
    InterruptedSession interrupt(Long subTaskId);
}
