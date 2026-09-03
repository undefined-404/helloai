package com.helloai.core.agent.runtime;

import com.helloai.core.agent.event.AgentEventRecorder;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * Agent 运行时执行上下文（Phase 0 C1）。
 *
 * <p>一次 {@link AgentRuntime#execute} 的完整输入：Run/Turn/Step 定位 + 执行主体 + 能力（技能/工具）
 * + 事件记录器。风格与旧执行链输入 {@code agent.domain.AgentTask} 一致：不可变值对象 + Builder。</p>
 *
 * <p>Phase 0 仅定义契约（供 C2 LegacyExecutorAdapter / C3 新 Runtime 实现消费），
 * 本上下文的生产方在双轨切换后才出现。</p>
 *
 * @see AgentRuntime
 */
@Value
@Builder
public class AgentContext {

    /** Run 标识（run-{taskId}-{roundNum}，ADR-001；与 B2 埋点同源，不可空）。 */
    String runId;

    /** 主任务 ID（可空：Run 级执行场景）。 */
    Long taskId;

    /** 子任务 ID（可空：Run/Task 级执行场景）。 */
    Long subTaskId;

    /** Turn 序号（一次 Agent 完整工作周期，从 1 起；与 {@code AgentEventContextResolver} 同语义）。 */
    int turn;

    /** Turn 内原子动作序号（从 0 起；0 表示非 Step 级事件）。 */
    int step;

    /** 执行 Agent ID（可空：未定 Agent 的场景）。 */
    Long agentId;

    /** 技能名列表（与 {@code AgentSelector.requiredSkills} 同表示；null/空 = 不限定）。 */
    @Builder.Default
    List<String> skills = Collections.emptyList();

    /** 启用工具名列表（与 {@code AgentMcpServerServiceImpl.getEnabledTools} 同表示；null/空 = 无工具）。 */
    @Builder.Default
    List<String> tools = Collections.emptyList();

    /** 事件记录器：执行过程事件经其落 append-only 事件流（B1 双写；按事件 write-only 纪律使用）。 */
    AgentEventRecorder eventRecorder;

    /**
     * 执行环境（坑 4 预留）：Phase 0 不实现任何 Sandbox，恒为 null；
     * Phase 1 提供 RemoteAgentEnvironment / LocalProcessEnvironment 实现后注入。
     */
    ExecutionEnvironment environment;
}