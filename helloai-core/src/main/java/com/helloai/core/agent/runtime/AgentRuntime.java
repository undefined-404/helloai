package com.helloai.core.agent.runtime;

/**
 * Agent 运行时（Phase 0 C1，双轨 → Runtime 契约起点）。
 *
 * <p>向调度 / 执行链上层暴露「单个 Task 的执行」统一契约
 * （执行方案术语：Agent Runtime = 单个 Task 的执行 How），
 * 屏蔽底层执行形态（旧平台执行器 / 远程 Agent / 未来 Sandbox）。
 * 事件契约即接口：执行过程事件经 {@link AgentContext#eventRecorder}
 * 落 append-only 事件流（B1 双写 + B2 埋点已铺底）。</p>
 *
 * <p>Phase 0 仅定义契约，不提供实现（C2 {@code LegacyExecutorAdapter}
 * 才落地第一个实现并接入 Feature Toggle 灰度；C3 新 Runtime 接管）。
 * 流式 / 异步 / 取消等扩展后续演进，本接口保持最小。</p>
 *
 * @see AgentContext
 * @see AgentExecutionResult
 */
public interface AgentRuntime {

    /**
     * 执行一次 Agent 工作周期（Turn 级）。
     *
     * @param ctx 执行上下文（含 Run/Turn/Step 定位与事件记录器；不可空）
     * @return 执行结果契约；失败 / 超时不抛异常，以 {@link com.helloai.common.constant.ExecutionStatus} 表达
     */
    AgentExecutionResult execute(AgentContext ctx);
}