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
import com.helloai.core.agent.tool.ToolContext;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.agent.tool.impl.DescribedToolCallback;
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
 * <p>装配（G-002 单轨，2026-09-30）：作为唯一 {@code AgentRuntime} 实现，经消费者
 * {@code List<AgentRuntime>} 注入收敛到 {@code agentRuntimes.get(0)}（Router / Legacy 适配器
 * 已删，无切换语义）。上下文装配（prompt / chatModel / 会话）由 {@code AgentRuntimeContextAssembler}
 * 完成，结果回写由消费者 {@code ExecutionResultHandler} 完成。</p>
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

        // 4. TOOL_RESOLVED（step=6）：启用工具解析「生效形态」（ToolRegistry 契约恒非 null）
        // G-004 增量 A：启用清单 = 上下文 tools ∪ 命中技能 requiredTools（并集去重保序），
        // 无技能时行为与旧版一致（mergeTools 原样返回）
        List<String> enabledTools = mergeTools(ctx.getTools(), resolved.requiredTools());
        // REF-1.3：解析上下文只装配调用点手头已有的事实（不臆造字段）；
        // resolve 的结果自此成为「模型可见工具的唯一判据」（见下方 resolveVisibleCallbacks）
        ToolContext toolContext = new ToolContext(ctx.getAgentId(), ctx.getTaskId(), ctx.getSubTaskId(),
                ctx.getTurn(), ctx.getAccessType(), resolved.requiredSkills());
        List<ToolDefinition> resolvedTools = toolRegistry.resolve(enabledTools, toolContext);
        if (resolvedTools == null) {
            resolvedTools = List.of();
        }
        // 事件可观测：tools = 请求集（原语义不变，外部读取零回归）；
        // effectiveTools = 模型实际可见集（摘除的可观测证据）；removedTools = 被摘除项（诊断）
        List<String> effectiveTools = resolvedTools.stream().map(ToolDefinition::name).toList();
        Set<String> effectiveToolSet = new HashSet<>(effectiveTools);
        List<String> removedTools = enabledTools.stream()
                .filter(name -> !effectiveToolSet.contains(name))
                .toList();
        record(ctx, 6, AgentEventType.TOOL_RESOLVED,
                safeMap("tools", enabledTools,
                        "effectiveTools", effectiveTools,
                        "removedTools", removedTools,
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

        // 7. AgentLoop 执行（内部按 TOOL_CALL_STARTED=3 / TOOL_CALL_COMPLETED=4 记录；
        // 每轮循环边界经 loopCheckpointListener 落进度快照，write-only 旁路）
        AgentLoopResult loopResult = agentLoop.run(new AgentLoopInput(
                ctx.getChatModel(),
                ctx.getSystemPrompt(),
                ctx.getUserPrompt(),
                toolExecutor,
                resolveVisibleCallbacks(resolvedTools),
                null,
                ctx.getRunId(), ctx.getTaskId(), ctx.getSubTaskId(), ctx.getTurn(), ctx.getAgentId(),
                ctx.getEventRecorder(),
                ctx.getLoopCheckpointListener()));

        // 8. AGENT_COMPLETED（step=0，Turn 端点）；失败终态以结果表达，不发失败事件（ADR §5.3）
        record(ctx, 0, AgentEventType.AGENT_COMPLETED,
                safeMap("iterations", loopResult.iterations(),
                        "toolCalls", loopResult.toolCallCount(),
                        "tokens", loopResult.tokenUsage(),
                        "finishReason", loopResult.finishReason()));
        if (loopResult.success()) {
            // A（2026-10-05 空产出护栏）：成功终态下若「最终正文为空白（StringUtils.isBlank 口径：
            // null / "" / 纯空白字符均算空）」且「工具调用数 == 0」，判为**可重试失败**而非 SUCCESS。
            // 依据：模型返回 STOP 但既无任何可见正文、也无任何工具调用 ⇒ 本次未产出任何交付物
            // （典型触发：推理模型把输出全放进 reasoning_content，正文被解析为空）。
            // ★必须保留 toolCallCount() == 0：否则会误伤「靠工具 / 附件交付产物、正文本来就为空」
            //   的合法成功（此时附件存在，核验侧 no_output_no_attachment 也不会触发）。
            // 失败走既有失败链（消费侧 markFailed → handleFailure → block → 退避重派），不新造旁路。
            if (isBlank(loopResult.text()) && loopResult.toolCallCount() == 0) {
                return AgentExecutionResult.builder()
                        .status(ExecutionStatus.FAILED)
                        .output("agent_runtime_empty_output: 模型返回空正文（finishReason="
                                + loopResult.finishReason() + ", toolCalls=0），无任何交付物，判定为可重试失败")
                        .thinking(loopResult.thinking())
                        .finishReason(loopResult.finishReason())
                        .tokenUsage(loopResult.tokenUsage())
                        .build();
            }
            return AgentExecutionResult.builder()
                    .status(ExecutionStatus.SUCCESS)
                    .output(loopResult.text())
                    .thinking(loopResult.thinking())
                    .finishReason(loopResult.finishReason())
                    .tokenUsage(loopResult.tokenUsage())
                    .build();
        }
        return AgentExecutionResult.builder()
                .status(ExecutionStatus.FAILED)
                .output(loopResult.errorMessage() != null ? loopResult.errorMessage() : loopResult.text())
                .finishReason(loopResult.finishReason())
                .tokenUsage(loopResult.tokenUsage())
                .build();
    }

    /**
     * 按 registry 的「生效形态」装配模型可见 ToolCallback（REF-1.3 生效面；
     * AgentLoop 可见 schema 与可调工具一致；best-effort：解析失败返回已装配部分 / 空列表）。
     *
     * <p>与改造前（{@code resolveEnabledCallbacks(names)}）的差别：过滤依据由「入参名字集合」
     * 改为「{@code resolve} 结果」——前者不含条件可用语义，只做名字匹配；后者是摘除 + 描述重写
     * 之后的生效形态。描述被改写的工具以 {@link DescribedToolCallback} 委托包装，
     * 使<b>模型读到的 schema 与生效描述一致</b>。</p>
     *
     * <p>顺序 = provider 注册顺序（与改造前一致 ⇒ schema 顺序零变化）；描述相同或生效描述为空白
     * ⇒ 原回调直接透传（零包装、零开销）。</p>
     *
     * <p>注意空列表的语义变化：{@code resolvedTools} 为空<b>不再等价于</b> {@code enabledTools}
     * 为空——「全部被条件可用摘除」是合法情形，此时提前返回既正确又省掉一次 provider 遍历。</p>
     */
    private List<ToolCallback> resolveVisibleCallbacks(List<ToolDefinition> resolvedTools) {
        if (resolvedTools == null || resolvedTools.isEmpty()) {
            return List.of();
        }
        Map<String, String> effective = new LinkedHashMap<>();
        for (ToolDefinition definition : resolvedTools) {
            effective.put(definition.name(), definition.description());
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        try {
            ToolCallback[] all = toolCallbackProvider.getToolCallbacks();
            if (all != null) {
                for (ToolCallback callback : all) {
                    if (callback == null || callback.getToolDefinition() == null) {
                        continue;
                    }
                    String name = callback.getToolDefinition().name();
                    if (!effective.containsKey(name)) {
                        continue; // 未启用 / 被条件可用摘除 ⇒ 模型不可见
                    }
                    String description = effective.get(name);
                    if (description == null || description.isBlank()
                            || description.equals(callback.getToolDefinition().description())) {
                        callbacks.add(callback); // 无生效描述 / 描述未变 ⇒ 原样透传
                    } else {
                        callbacks.add(new DescribedToolCallback(callback, description));
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
