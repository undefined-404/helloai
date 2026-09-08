package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.sandbox.Sandbox;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime 真身（P0-B）：把 P0-C 八件套组装为一次 Agent Turn 的真实执行器。
 *
 * <p>输入 {@link AgentContext}（prompt / chatModel 由调用方注入，缺失时契约化失败）+ 注入
 * AgentLoop / ToolExecutor / ToolRegistry / AgentSkillSpecService / SandboxProvider。
 * 职责 = 一次 Turn 的执行：沙箱策略观测 → Turn 事件骨架（AGENT_STARTED → SKILL_RESOLVED /
 * TOOL_RESOLVED / ENVIRONMENT_RESOLVED → CONTEXT_BUILT → [AgentLoop TOOL_CALL 事件]
 * → AGENT_COMPLETED）→ {@link AgentExecutionResult}。</p>
 *
 * <p>边界：不依赖 Planner / Scheduler / Reviewer / Task Service（Runtime 红线）；
 * 事件 write-only（记录失败不阻断）；上下文装配（prompt 构建）由调用方完成并注入，
 * 本类不复制旧链业务编排（差距表 §6 禁止复制完整业务链）。</p>
 *
 * <p>装配：作为 {@code AgentRuntime} 候选实现之一，经 {@link RuntimeAgentRuntimeRouter}
 * 按 {@code runtime-enabled} 开关切换（本类 @Order(3)，仅路由引用；消费者 List 取序不受影响）。</p>
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class RuntimeTurnExecutor implements AgentRuntime {

    private final AgentLoop agentLoop;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final AgentSkillSpecService agentSkillSpecService;
    private final SandboxProvider sandboxProvider;
    private final ToolCallbackProvider toolCallbackProvider;

    @Override
    public AgentExecutionResult execute(AgentContext ctx) {
        if (ctx == null || ctx.getSubTaskId() == null || ctx.getAgentId() == null
                || ctx.getChatModel() == null
                || isBlank(ctx.getSystemPrompt()) && isBlank(ctx.getUserPrompt())) {
            return fail("subTaskId / agentId / chatModel / prompt 不可为空");
        }

        // 1. 沙箱策略观测（best-effort：accessType 缺失或无环境命中时跳过，仅日志）
        observeSandbox(ctx);

        // 2. Turn 事件骨架：AGENT_STARTED（step=1）
        record(ctx, 1, AgentEventType.AGENT_STARTED,
                safeMap("status", "IN_PROGRESS", "assignedAgentId", ctx.getAgentId()));

        // 3. SKILL_RESOLVED（step=5）：按上下文 skills 解析（接口契约 resolve 恒非 null，此处防御兜底）
        // G-004 增量 A：payload 携带命中技能版本（resolvedVersions）
        List<String> skills = ctx.getSkills() != null ? ctx.getSkills() : List.of();
        AgentSkillSpecService.ResolvedSpec resolved = agentSkillSpecService.resolve(skills);
        if (resolved == null) {
            resolved = new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), "");
        }
        record(ctx, 5, AgentEventType.SKILL_RESOLVED,
                safeMap("requiredSkills", resolved.requiredSkills(),
                        "resolvedSpecs", resolved.matchedLabels(),
                        "resolvedVersions", resolved.resolvedVersions()));

        // 4. TOOL_RESOLVED（step=6）：启用工具解析元数据（ToolRegistry 契约恒非 null）
        // G-004 增量 A：启用清单 = 上下文 tools ∪ 命中技能 requiredTools（并集去重保序），
        // 无技能时行为与旧版一致（mergeTools 原样返回）
        List<String> enabledTools = mergeTools(ctx.getTools(), resolved.requiredTools());
        List<ToolDefinition> resolvedTools = toolRegistry.resolve(enabledTools);
        if (resolvedTools == null) {
            resolvedTools = List.of();
        }
        record(ctx, 6, AgentEventType.TOOL_RESOLVED,
                safeMap("tools", enabledTools,
                        "resolvedTools", resolvedTools.stream()
                                .map(td -> Map.of("name", td.name(), "description", td.description()))
                                .toList()));

        // 5. ENVIRONMENT_RESOLVED（step=7）：环境标识 + 接入类型按事实记录
        record(ctx, 7, AgentEventType.ENVIRONMENT_RESOLVED,
                safeMap("environment", ctx.getEnvironment() != null ? ctx.getEnvironment().name() : null,
                        "accessType", ctx.getAccessType() != null ? ctx.getAccessType().name() : null));

        // 6. CONTEXT_BUILT（step=2）：提示词装配完成（promptChars 按注入正文统计，依赖装配属平台层）
        int promptChars = (ctx.getSystemPrompt() != null ? ctx.getSystemPrompt().length() : 0)
                + (ctx.getUserPrompt() != null ? ctx.getUserPrompt().length() : 0);
        record(ctx, 2, AgentEventType.CONTEXT_BUILT, safeMap("promptChars", promptChars));

        // 7. AgentLoop 执行（内部按 TOOL_CALL_STARTED=3 / TOOL_CALL_COMPLETED=4 记录）
        AgentLoopResult loopResult = agentLoop.run(new AgentLoopInput(
                ctx.getChatModel(),
                ctx.getSystemPrompt(),
                ctx.getUserPrompt(),
                toolExecutor,
                resolveEnabledCallbacks(enabledTools),
                null,
                ctx.getRunId(), ctx.getTaskId(), ctx.getSubTaskId(), ctx.getTurn(), ctx.getAgentId(),
                ctx.getEventRecorder()));

        // 8. AGENT_COMPLETED（step=0，Turn 端点）；失败终态以结果表达，不发失败事件（ADR §5.3）
        record(ctx, 0, AgentEventType.AGENT_COMPLETED,
                safeMap("iterations", loopResult.iterations(),
                        "toolCalls", loopResult.toolCallCount(),
                        "finishReason", loopResult.finishReason()));
        if (loopResult.success()) {
            return AgentExecutionResult.builder()
                    .status(ExecutionStatus.SUCCESS)
                    .output(loopResult.text())
                    .build();
        }
        return AgentExecutionResult.builder()
                .status(ExecutionStatus.FAILED)
                .output(loopResult.errorMessage() != null ? loopResult.errorMessage() : loopResult.text())
                .build();
    }

    /**
     * 按启用工具名过滤 ToolCallback 目录（AgentLoop 可见 schema 与可调工具一致；
     * best-effort：解析失败返回空列表，循环内无工具）。
     */
    private List<ToolCallback> resolveEnabledCallbacks(List<String> enabledTools) {
        if (enabledTools == null || enabledTools.isEmpty()) {
            return List.of();
        }
        Set<String> names = new HashSet<>(enabledTools);
        List<ToolCallback> callbacks = new ArrayList<>();
        try {
            ToolCallback[] all = toolCallbackProvider.getToolCallbacks();
            if (all != null) {
                for (ToolCallback callback : all) {
                    if (callback != null && callback.getToolDefinition() != null
                            && names.contains(callback.getToolDefinition().name())) {
                        callbacks.add(callback);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("RuntimeTurnExecutor: 工具回调解析失败（循环内无工具）: err={}", e.getMessage());
        }
        return callbacks;
    }

    /**
     * 启用工具并集（G-004 增量 A）：上下文 tools（agent 侧可用工具）∪ 命中技能
     * requiredTools（技能声明工具）。LinkedHashSet 去重保序——上下文工具在前、技能声明
     * 工具按 resolve 返回序追加；纯函数式，不查询任何域。技能未声明工具时原样返回。
     */
    private static List<String> mergeTools(List<String> tools, List<String> skillRequiredTools) {
        if (skillRequiredTools == null || skillRequiredTools.isEmpty()) {
            return tools != null ? tools : List.of();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (tools != null) {
            merged.addAll(tools);
        }
        merged.addAll(skillRequiredTools);
        return List.copyOf(merged);
    }

    /** 沙箱策略观测（best-effort 日志，不参与业务决策）。 */
    private void observeSandbox(AgentContext ctx) {
        if (ctx.getAccessType() == null) {
            return;
        }
        try {
            Sandbox sandbox = sandboxProvider.resolve(new SandboxContext(
                    ctx.getAccessType(), ctx.getTaskId(), ctx.getSubTaskId(), ctx.getAgentId()));
            if (sandbox != null) {
                log.info("RuntimeTurnExecutor: sandbox resolved env={}, policy={}",
                        sandbox.environment().name(), sandbox.policy());
            }
        } catch (Exception e) {
            log.warn("RuntimeTurnExecutor: 沙箱解析失败（不阻断执行）: err={}", e.getMessage());
        }
    }

    private void record(AgentContext ctx, int step, AgentEventType eventType, Map<String, Object> payload) {
        AgentEventRecorder recorder = ctx.getEventRecorder();
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(ctx.getRunId(), ctx.getTaskId(), ctx.getSubTaskId(), ctx.getTurn(),
                    step, eventType, ctx.getAgentId(), payload);
        } catch (Exception e) {
            log.warn("RuntimeTurnExecutor: 事件记录失败（write-only 不阻断）: type={}, err={}",
                    eventType, e.getMessage());
        }
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AgentExecutionResult fail(String message) {
        return AgentExecutionResult.builder()
                .status(ExecutionStatus.FAILED)
                .output(message)
                .build();
    }
}
