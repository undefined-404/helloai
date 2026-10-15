package com.helloai.core.agent.event;

import com.helloai.common.constant.AgentEventType;

import java.util.Map;

/**
 * Agent 事件记录器（ADR-001 Run/Turn/Step 模型配套）。
 *
 * <p>同事务双写：
 * <ol>
 *   <li>{@code agent_event}（append-only 事件轨迹，Event Stream 主表）</li>
 *   <li>{@code agent_outbox_event}（Outbox，由 {@code AgentEventCompensationTask} 投递 MQ）</li>
 * </ol>
 * 两表共享同一 {@code eventId}（B3 对账键）；事件不参与<b>业务状态决策</b>（B2 埋点纪律）——
 * 注意「不参与决策」<b>不等于</b>「写失败不阻断事务」，见 {@link #record} 的事务语义。</p>
 */
public interface AgentEventRecorder {

    /**
     * 记录一次事件。
     *
     * <p><b>★事务语义（2026-10-15 订正，务必读完再决定是否 try-catch）</b>：
     * 本方法 {@code @Transactional(REQUIRED)}，与调用方事务<b>合并为同一个事务</b>
     * （同线程传播；调用方无事务时自开）。
     * 任一遍写失败 ⇒ <b>整个共享事务被标记 rollback-only</b> ⇒ 调用方提交时抛
     * {@code UnexpectedRollbackException}，<b>调用方的业务变更一并回滚</b>。</p>
     *
     * <p><b>⚠ 因此「调用方 try-catch 即可不阻断主链路」是错的</b>：try-catch 只挡得住
     * <b>异常冒泡</b>，挡不住事务已被标记 —— 结果是「catch 之后继续做一段注定回滚的工作，
     * 最后在 commit 处抛出与根因无关的 {@code UnexpectedRollbackException}」，
     * 排查成本远高于直接在事件写点失败。这是刻意<b>不</b>采用 {@code REQUIRES_NEW} 的原因：
     * 那会产出「业务已回滚但事件已投递」的<b>幻影事件</b>，与
     * {@code EventReconciliationService} 明文的「事件与状态变更同事务、读到业务状态时事件必已落库」
     * 不变量（以及 CODE_STYLE §24「业务变更 + Outbox 同一事务」）直接冲突。</p>
     *
     * <p><b>正确用法</b>：① 若事件是业务链路的一环 ⇒ <b>直接调用、不要 try-catch</b>，
     * 让失败在<b>现场</b>暴露（fail-fast）；② 若确实希望「事件写失败不得影响业务」，
     * 须把调用挪到<b>提交后</b>（{@code TransactionSynchronization#afterCommit}，
     * 同仓既有范式见 {@code ExecutionResultHandler} 的产出物化段），
     * 而不是在事务内 try-catch 假装降级。</p>
     *
     * @param runId     Run 标识（run-{taskId}-{roundNum}，ADR-001；不可空）
     * @param taskId    主任务 ID（可空：非任务级事件）
     * @param subTaskId 子任务 ID（可空：Run/Task 级事件）
     * @param turn      Turn 序号（一次 Agent 完整工作周期，从 1 起）
     * @param step      Turn 内原子动作序号（从 0 起；0 表示非 Step 级事件）
     * @param eventType 事件类型（不可空）
     * @param agentId   关联 Agent ID（可空）
     * @param payload   事件负载（可空，空时存空 Map）
     */
    void record(String runId, Long taskId, Long subTaskId, int turn, int step,
                AgentEventType eventType, Long agentId, Map<String, Object> payload);
}