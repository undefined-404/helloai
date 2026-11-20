package com.helloai.core.agent.runtime;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.config.ToolCatalogProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.cancel.AgentCancelledException;
import com.helloai.core.agent.runtime.cancel.LoopCancellation;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.AgentUsage;
import com.helloai.core.agent.runtime.sandbox.Sandbox;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import com.helloai.core.shared.util.VerificationFence;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.EnabledToolNames;
import com.helloai.core.agent.tool.InternalToolCallbacks;
import com.helloai.core.agent.tool.ToolCatalogAssembler;
import com.helloai.core.agent.tool.ToolContext;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolInvocationContext;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.agent.tool.impl.VisibleToolCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p>装配（G-002 单轨）：作为唯一 {@code AgentRuntime} 实现，经消费者
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

    /**
     * 内部执行链专用工具（REF-8.1 B，{@code internal_} 前缀）——不进对外 MCP {@code tools/list}，
     * 与对外面合成唯一目录。非构造注入：既有 6 参构造器与单测零改动，缺失时空集合（行为等价）。
     */
    @Autowired(required = false)
    private InternalToolCallbacks internalToolCallbacks;

    /**
     * 执行链配置（REF-8.2 maxIterations 分档）。非构造注入（field + {@code @Autowired}），
     * 保持既有 6 参构造器不变（复用其显式构造的测试零改动）；Spring 装配时
     * {@code Optional<AgentExecutionProperties>} 语义，缺失时回落 {@link #resolveMaxIterations(int)} 默认。
     */
    @Autowired
    private AgentExecutionProperties executionProperties;

    /**
     * 工具目录装配配置（MA-5 第一步）：模型可见描述摘要封顶 + 白名单。非构造注入（field +
     * {@code @Autowired}），保持既有 6 参构造器不变（复用其显式构造的测试零改动）；
     * 单测显式构造时该字段为 null，{@code resolveVisibleCallbacks} 内回落
     * {@code new ToolCatalogProperties()}（默认 160 / 白名单，行为确定）。
     */
    @Autowired
    private ToolCatalogProperties toolCatalogProperties;

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
        int systemPromptChars = ctx.getSystemPrompt() != null ? ctx.getSystemPrompt().length() : 0;
        int promptChars = systemPromptChars
                + (ctx.getUserPrompt() != null ? ctx.getUserPrompt().length() : 0);
        // systemPromptChars 单列：稳定面（角色/persona 槽位/工具指引/输出契约）的长度——REF-8.1.2 生效的可观测面
        record(ctx, 2, AgentEventType.CONTEXT_BUILT,
                safeMap("promptChars", promptChars, "systemPromptChars", systemPromptChars));

        // 7. AgentLoop 执行（内部按 TOOL_CALL_STARTED=3 / TOOL_CALL_COMPLETED=4 记录；
        // 每轮循环边界经 loopCheckpointListener 落进度快照，write-only 旁路）
        // REF-8.1 A1：绑定本轮执行链真实标识——内部 LLM 无从自报 agentId / subTaskId，改由
        // ToolCallbackToolExecutor 经 ToolInvocationContext 在调用 internal_* 工具前带外注入。
        // 严格 try/finally 成对解绑（防线程复用串味）；不触碰 ChatModelToolLoop 本体（§10.5 红线）。
        // `SL-4`：同时绑定执行轮次——沙箱台账条目要能回答「这是第几轮跑的」（证据轮次优先）。
        // `SL-8`：一并绑定 taskId——任务级工作区句柄不再需要为每条命令二次查库。
        ToolInvocationContext.bind(ctx.getAgentId(), ctx.getTaskId(), ctx.getSubTaskId(), ctx.getTurn());
        AgentLoopResult loopResult;
        try {
            loopResult = agentLoop.run(new AgentLoopInput(
                    ctx.getChatModel(),
                    ctx.getSystemPrompt(),
                    ctx.getUserPrompt(),
                    toolExecutor,
                    resolveVisibleCallbacks(resolvedTools),
                    resolveMaxIterations(ctx),
                    ctx.getRunId(), ctx.getTaskId(), ctx.getSubTaskId(), ctx.getTurn(), ctx.getAgentId(),
                    ctx.getEventRecorder(),
                    ctx.getLoopCheckpointListener(),
                    ctx.getAccessType(),
                    resolveCancellation(),
                    null));
        } catch (AgentCancelledException e) {
            // REF-8.6：取消是独立终态（不是「模型/工具出错」）——明确落 finishReason=CANCELLED，
            // 让消费侧能按取消来源决定后续动作（而非当成普通失败退避重派）。
            log.warn("RuntimeTurnExecutor: 本轮执行被取消: agentId={}, subTaskId={}, reason={}",
                    ctx.getAgentId(), ctx.getSubTaskId(), e.getMessage());
            return AgentExecutionResult.builder()
                    .status(ExecutionStatus.FAILED)
                    .output("agent_runtime_cancelled: " + e.getMessage())
                    .finishReason("CANCELLED")
                    .build();
        } finally {
            ToolInvocationContext.unbind();
        }

        // 8. AGENT_COMPLETED（step=0，Turn 端点）；失败终态以结果表达，不发失败事件（ADR §5.3）
        // REF-8.8：用量以互斥分量 + 耗时落同一事件（provider 未给的分量为 null——降级不报错，
        // `tokens` 保留为总数，既有消费方零改动）。
        AgentUsage usage = loopResult.usage();
        record(ctx, 0, AgentEventType.AGENT_COMPLETED,
                safeMap("iterations", loopResult.iterations(),
                        "toolCalls", loopResult.toolCallCount(),
                        "tokens", loopResult.tokenUsage(),
                        "finishReason", loopResult.finishReason(),
                        "inputTokens", usage != null ? usage.inputTokens() : null,
                        "outputTokens", usage != null ? usage.outputTokens() : null,
                        "cacheReadTokens", usage != null ? usage.cacheReadTokens() : null,
                        "cacheWriteTokens", usage != null ? usage.cacheWriteTokens() : null,
                        "reasoningTokens", usage != null ? usage.reasoningTokens() : null,
                        "latencyMs", usage != null ? usage.latencyMillis() : null));
        if (loopResult.success() || loopResult.isPartialDelivery()) {
            // A（空产出护栏）：成功终态下若「最终正文为空白（StringUtils.isBlank 口径：
            // null / "" / 纯空白字符均算空）」且「工具调用数 == 0」，判为**可重试失败**而非 SUCCESS。
            // 依据：模型返回 STOP 但既无任何可见正文、也无任何工具调用 ⇒ 本次未产出任何交付物
            // （典型触发：推理模型把输出全放进 reasoning_content，正文被解析为空）。
            // ★必须保留 toolCallCount() == 0：否则会误伤「靠工具 / 附件交付产物、正文本来就为空」
            //   的合法成功（此时附件存在，核验侧 no_output_no_attachment 也不会触发）。
            // 失败走既有失败链（消费侧 markFailed → handleFailure → block → 退避重派），不新造旁路。
            // ★MA-10：本护栏同时覆盖「尽力交付」分支（isPartialDelivery 已要求正文非空，
            //   此处命中只可能是 naturalStop 分支，语义不变）。
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
            // REF-8.4 ④ 出口分类（对齐 DSH `turn-stopping` + 复用「空产出护栏」同层位置）：
            // 内部 LLM 自然 STOP 的最终报告若缺 VERIFICATION 围栏 ⇒ 输出契约 fail-close（评审侧同源），
            // 判 FAILED 而非 SUCCESS——但**只改 ExecutionStatus，不碰 SubTaskStatus**（红线①反证）。
            // 口径 A（决策 `D-2026-10-13-6`）：
            //   ① 空正文一律交给上面 Guard A 判定（空+0工具=fail / 空+工具=靠附件/工具交付合法 SUCCESS），
            //     故此处显式跳过空正文（!isBlank），不因「空 ⇒ 无 VERIFICATION」重复误杀——生产空报告的
            //     自证由 ② 收尾轮补齐，此处只管「非空但无自证痕迹」。
            //   ② 用宽松 `containsMarker`（有 VERIFICATION: 即放行），不含 STATUS 也放行——小环只拦
            //     「完全没有自证痕迹」的报告，质量/自证充分度统一由平台 reviewer 大环兜底（评审→REWORK）。
            //   ⇒ ② 收尾轮的 `adequate()`（要求 STATUS）是「尽力让模型写完整」，与此处宽松闸不冲突。
            // ★MA-10 改动 1：**尽力交付不适用本闸**。此闸的语义是「自然终态的交付物必须自证」，
            //   而轮次预算用尽时模型没有第 21 轮可以补围栏——按本闸判失败等于把「尽力交付」
            //   重新变回「失败」，与改动 1 的方向相反。真值由 finishReason=MAX_ITERATIONS 透出，
            //   质量兜底仍在评审大环（REVIEW → REWORK），不在这里加一道必然命中的闸。
            boolean naturalStop = loopResult.success();
            if (naturalStop
                    && ctx.getAccessType() == AgentAccessType.API_KEY_LLM
                    && !isBlank(loopResult.text())
                    && !VerificationFence.containsMarker(loopResult.text())) {
                return AgentExecutionResult.builder()
                        .status(ExecutionStatus.FAILED)
                        .output("agent_runtime_missing_verification: 最终报告未携带 VERIFICATION 围栏，"
                                + "无法自证交付（输出契约 fail-close，评审侧同源判定），判定为可重试失败")
                        .thinking(loopResult.thinking())
                        .finishReason(loopResult.finishReason())
                        .tokenUsage(loopResult.tokenUsage())
                        .build();
            }
            if (!naturalStop) {
                // MA-10 改动 1：轮次预算用尽但有正文 ⇒ 判 SUCCESS（尽力交付）。
                // finishReason 保留 MAX_ITERATIONS —— 下游（评审 / MA-4 交付闸 / 运维）据此区分
                // 「自然完成」与「预算用尽」，不做静默归一（判据：真值不掩盖）。
                log.warn("RuntimeTurnExecutor: 轮次预算用尽，按尽力交付判成功: agentId={}, subTaskId={}, "
                                + "iterations={}, toolCalls={}, textLen={}",
                        ctx.getAgentId(), ctx.getSubTaskId(), loopResult.iterations(),
                        loopResult.toolCallCount(), loopResult.text().length());
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
     * <p><b>REF-8.5 起</b>：过滤本体已抽到 {@link VisibleToolCallbacks}（业务面循环入口共用
     * 同一实现，防「第二处二次过滤」）。<b>MA-5 起</b>：在委派前先经
     * {@link ToolCatalogAssembler} 做目录装配（模型可见描述 ≤160 字符摘要、白名单工具保留
     * 完整描述）——名字 / 顺序 / 数量不变，故 {@code TOOL_RESOLVED} 事件的
     * {@code tools / effectiveTools / removedTools} 与上层语义零变化（外部读取零回归），
     * 完整描述改由 {@code internal_load_tool} 按需加载。</p>
     */
    private List<ToolCallback> resolveVisibleCallbacks(List<ToolDefinition> resolvedTools) {
        // REF-8.5：过滤逻辑抽到 VisibleToolCallbacks（业务面循环入口共用同一实现，
        // 保证「模型可见工具 = ToolRegistry.resolve 结果」这一单口径不在两处各写一份）。
        // 可见回调源 = 对外 MCP 面 ∪ 内部执行链专用面（REF-8.1 B，internal_ 前缀在后）。
        // MA-5 第一步：目录装配只换 description 内容（摘要 / 白名单全文），不换 name 与顺序
        // ⇒ effectiveTools 语义在装配前后逐字一致；摘要后的完整描述由 internal_load_tool 取。
        ToolCatalogProperties catalogProps = toolCatalogProperties != null ? toolCatalogProperties
                : new ToolCatalogProperties();
        List<ToolDefinition> visibleTools = ToolCatalogAssembler.assemble(
                resolvedTools, catalogProps.getDescriptionMaxLength(),
                new HashSet<>(catalogProps.getFullDefinitionTools()));
        return VisibleToolCallbacks.select(visibleTools,
                InternalToolCallbacks.union(toolCallbackProvider.getToolCallbacks(), internalToolCallbacks));
    }

    /**
     * 轮次预算的**结构性下限**（`MA-10` 改动 2）。
     *
     * <p><b>为什么不可配置化</b>：{@code ChatModelToolLoop} 用
     * {@code forceFinalizeFromIteration = max(1, maxIterations - 1)} 决定从第几轮起禁用工具、
     * 强制出正文。{@code maxIterations <= 1} 时该护栏<b>从第 1 轮就生效</b>——模型连一次工具
     * 都调不了，「尽力交付」退化成「凭空作文」。故 2 是循环体的结构性约束，不是运维偏好。</p>
     */
    private static final int MIN_MAX_ITERATIONS = 2;

    /**
     * 解析 AgentLoop 最大循环轮数（`REF-8.2` 分档 + `MA-10` 改动 2 子任务级覆盖）。
     *
     * <p><b>优先级</b>：子任务级 {@code maxIterationsHint}（钳制后）&gt; 接入类型分档默认。
     * hint 未给 / 非法 / 非正 ⇒ 回落分档默认，行为与改造前逐字一致（这是改动 2 的第三条要求）。</p>
     *
     * <p>内部 LLM 执行者（{@code API_KEY_LLM}）要多步调工具 / 读附件 / 写产物，独立档位默认更高
     * （{@link AgentExecutionProperties#getInternalMaxIterations()} 默认 20）；其余接入类型
     * （外部 Agent / null）回落到外部档（默认 5，与 {@code AgentLoopInput.DEFAULT_MAX_ITERATIONS} 一致）。
     * 配置 Bean 缺失或档位 <=0 时回落硬默认，保证任何环境行为确定。</p>
     *
     * <p><b>off-by-one（改数值时必须同步检查）</b>：{@code forceFinalizeFromIteration = maxIterations - 1}
     * ⇒ <b>实际可用工具轮次 = {@code maxIterations - 1}</b>，最后 2 轮被禁用工具强制收尾。
     * 所以「hint 给 20」的实际含义是「18 轮可调工具 + 2 轮收尾」，调钳制边界时要按这个口径算，
     * 否则会出现「加了预算但实际没变」（蓝图 §4 `MA-10` 改动 2 的显式提醒）。</p>
     */
    private Integer resolveMaxIterations(AgentContext ctx) {
        int fallback = resolveTierMaxIterations(ctx);
        Integer hint = clampHint(ctx.getMaxIterationsHint(), fallback);
        return hint != null ? hint : fallback;
    }

    /** 接入类型分档默认（`REF-8.2`）：内部执行者高、其余低；配置缺失/非法回落硬默认。 */
    private int resolveTierMaxIterations(AgentContext ctx) {
        boolean internal = ctx.getAccessType() == AgentAccessType.API_KEY_LLM;
        AgentExecutionProperties props = executionProperties;
        if (props == null) {
            return internal ? AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA
                    : AgentLoopInput.DEFAULT_MAX_ITERATIONS;
        }
        int configured = internal ? props.getInternalMaxIterations() : props.getExternalMaxIterations();
        return configured > 0 ? configured
                : (internal ? AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA : AgentLoopInput.DEFAULT_MAX_ITERATIONS);
    }

    /**
     * 对子任务级 hint 做区间钳制（`MA-10` 改动 2）。
     *
     * <p>钳制区间 {@code [MIN_MAX_ITERATIONS, maxIterationsHintCeiling]}。上限来自配置
     * （{@link AgentExecutionProperties#getMaxIterationsHintCeiling()}，默认 50），因为「多给几轮
     * 可以接受多少成本」是部署决策；下限是循环体结构约束（见 {@link #MIN_MAX_ITERATIONS}），
     * 不是部署决策，故不可配置。</p>
     *
     * <p><b>越界要打日志</b>：planner 给了 500 被钳到 50，这件事必须能事后查到——否则
     * 「为什么这个子任务跑到一半就收尾了」永远归因不到「hint 被钳了」。日志只记数值不记上下文，
     * 不含任何提示词正文。</p>
     *
     * @return 钳制后的值；{@code null} = 无可用 hint，调用方回落分档默认
     */
    private Integer clampHint(Integer hint, int fallback) {
        if (hint == null || hint <= 0) {
            // 非正值 = 「未给」而不是「给得很少」：装配层已过滤过一遍，但 AgentContext 的构造方
            // 不止装配层（单测 / 将来的外部调用方都可能直接给），本类才是唯一消费点，
            // 故这里必须再收一次口（判据 1：规则存在 ≠ 全路径强制）。
            // 若把 0 / 负数当「给得很少」去抬到下限 2，`maxIterationsHint=0` 会静默变成
            // 「只给 2 轮」，比「回落 20 轮」错得多。
            return null;
        }
        AgentExecutionProperties props = executionProperties;
        int ceiling = props != null ? props.getMaxIterationsHintCeiling() : 50;
        // 上限不得低于下限，否则「钳制」会把所有 hint 都压成非法区间（配置写错也不该炸执行链）
        int effectiveCeiling = Math.max(MIN_MAX_ITERATIONS, ceiling);
        int clamped = Math.max(MIN_MAX_ITERATIONS, Math.min(hint, effectiveCeiling));
        if (clamped != hint) {
            log.info("RuntimeTurnExecutor: maxIterationsHint 越界已钳制: raw={}, clamped={}, range=[{}, {}]",
                    hint, clamped, MIN_MAX_ITERATIONS, effectiveCeiling);
        }
        if (clamped < fallback) {
            // hint 比档位默认还低是合法意图（「这活儿我只要 3 轮」），但会缩短可用工具轮次，
            // 值得留痕——排查「为什么提前收尾」时要能区分「模型没做完」与「预算被调小了」。
            log.debug("RuntimeTurnExecutor: maxIterationsHint 低于档位默认: hint={}, tierDefault={}",
                    clamped, fallback);
        }
        return clamped;
    }

    /**
     * 可取消上下文（REF-8.6 + `MA-10` 改动 4）：子任务链<b>只装配单工具超时</b>，不装本轮总时长闸。
     *
     * <p><b>为什么删总时长</b>：{@code turnDeadlineSeconds=1800} 在子任务链上从未有机会开火——
     * 外层 {@code runningTimeoutMinutes=10} 先到。留着它是「两道时间闸并存」，
     * 会让「为什么这个任务 12 分钟死、那个 40 分钟才死」永远解释不了（蓝图 §4 `MA-10` ⑥）。
     * 次数才是主闸：总时长的上界由「轮次 × 单轮」给出，而不是一个拍出来的固定值。</p>
     *
     * <p><b>业务面为什么不跟着删</b>：{@code BusinessSurfaceLoopRunner} 那里没有
     * {@code agent_execution_record}，也就没有「无进展」兜底——总时长闸是它<b>唯一</b>的异常保护，
     * 故保留（蓝图 §4 `MA-10` 改动 4 明确）。</p>
     *
     * <p>配置 Bean 缺失（单测显式构造）⇒ {@link LoopCancellation#none()}，行为与改造前逐字一致。</p>
     */
    private LoopCancellation resolveCancellation() {
        AgentExecutionProperties props = executionProperties;
        if (props == null) {
            return LoopCancellation.none();
        }
        return LoopCancellation.ofToolTimeoutOnly(props.getToolCallTimeoutMillis());
    }

    /**
     * 启用工具并集（G-004 增量 A）：上下文 tools（agent 侧可用工具）∪ 命中技能 requiredTools。
     *
     * <p><b>REF-8.5 起</b>：实现抽到 {@link EnabledToolNames#union}（三处副本合一，
     * 防「并集口径」漂移）；本方法保留为语义命名点。</p>
     */
    private static List<String> mergeTools(List<String> tools, List<String> skillRequiredTools) {
        return EnabledToolNames.union(tools, skillRequiredTools);
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
