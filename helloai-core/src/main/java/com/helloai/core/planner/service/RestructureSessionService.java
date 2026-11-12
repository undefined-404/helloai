package com.helloai.core.planner.service;

/**
 * 执行期重构会话（`ADR-008` §13.2 / §16，AD-4）。
 *
 * <p>把 `RESTRUCTURING` 子任务「拆出去当主任务」：先开一条 ACTIVE 需求会话并把四段上下文
 * （原要求 / 失败轨迹 / 上游产出 / 下游期待）作为首条用户消息写入；用户与 Planner 讨论定稿后，
 * 由 {@code ClarifyFinalizeServiceImpl} 走 {@code TaskDerivationService#createRestructureTask}
 * 建独立任务 `Y` 并把父 SubTask 置 `AWAITING_DERIVED`。</p>
 */
public interface RestructureSessionService {

    /**
     * 为重构中的子任务开派生会话（幂等：已存在 ACTIVE 派生会话时直接返回其 id）。
     *
     * @param subTaskId      必须处于 `RESTRUCTURING`
     * @param plannerAgentId 可选：指定 Planner（null = 平台默认选人）
     * @return 会话 id
     */
    Long startSession(Long subTaskId, Long plannerAgentId);

    /**
     * 为**草案**开派生会话（`ADR-008` §5 草案期派生，幂等）。
     *
     * <p>与 {@link #startSession} 的差别只在锚与上下文来源：草案还没物化成子任务，
     * 没有失败轨迹/返工次数可讲，上下文换成「草案四要素 + 同任务兄弟草案的上下游关系」。
     * 之所以要开这条通道——草案「拆出去」原先直接建派生任务 Y 并让 Y 立刻自拆，
     * 执行前提（仓库 / 运行时 / 数据源）无人过问，Y 的子任务只能写「仓库中」，
     * 执行侧必然因缺仓库检出而人工介入。</p>
     *
     * @param parentTaskId   草案所属任务（须 `AWAITING_PLAN_CONFIRM`）
     * @param draftCode      草案编号（派生父锚的一半）
     * @param plannerAgentId 可选：指定 Planner（null = 平台默认选人）
     * @return 会话 id
     */
    Long startDraftSession(Long parentTaskId, String draftCode, Long plannerAgentId);
}
