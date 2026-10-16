package com.helloai.core.agent.runtime;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.runtime.cancel.AgentCancelledException;
import com.helloai.core.agent.runtime.cancel.LoopCancellation;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.EnabledToolNames;
import com.helloai.core.agent.tool.InternalToolCallbacks;
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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 业务面循环入口（REF-8.5 覆盖面收敛）。
 *
 * <p><b>为什么存在（实测事实，2026-10-16）</b>：内部 LLM 的工具循环此前<b>只</b>存在于子任务
 * 执行链——全仓生产代码 {@code agentLoop.run()} 唯一调用点是 {@link RuntimeTurnExecutor}。
 * 业务 LLM 面（拆解 / 子任务核验 / 终报告整合 / 终报告审查）全部经
 * {@code PlatformAgentExecutionService#executeSync} 走 {@code ApiKeyAgentExecutor} 的
 * <b>裸单次</b> {@code ChatModel} 调用：<b>无工具、无循环、无 hook、无上下文压缩</b>。
 * 于是「产出需查阅 / 校验外部证据」的面（如核验须读原始附件、整合须读各子任务产出）
 * 拿不到手脚，只能吃平台预内联的摘要。</p>
 *
 * <p><b>职责边界（一个面一次调用）</b>：判定 {@code scene} 是否在进循环白名单内，
 * 是则装配一次 {@code AgentLoop} 输入并执行，把 {@link AgentLoopResult} 映射回
 * {@link AgentResult}；否则返回 {@code null}，由调用方走原有单次链路。
 * <b>不做</b>：不装配 Prompt（{@code AgentTask} 已带）、不落业务状态、不写事件流、
 * 不落对话流、不进评审——那些是各业务面自己的编排职责（与 {@link RuntimeTurnExecutor}
 * 的类型边界一致，<b>不复制业务编排</b>）。</p>
 *
 * <p><b>为什么不复用 {@link RuntimeTurnExecutor}</b>：该类绑定的是<b>子任务执行链</b>契约——
 * 其入参守卫要求 {@code subTaskId} 非空、其出口分类（空产出护栏 / {@code VERIFICATION} 围栏
 * fail-close）是「子任务交付物必须自证」的契约、其事件骨架会向 {@code agent_event} 写入
 * {@code AGENT_STARTED} / {@code AGENT_COMPLETED} 等子任务级语义事件。业务面（3 / 4 个为
 * 任务级、无 {@code subTaskId}；输出是 JSON 草案 / 判定 / 报告，<b>不是</b>需自证的交付物）
 * 套用该契约会：① 直接被守卫拒绝；② 被围栏判定误杀为 FAILED；③ 向同一
 * {@code sub_task_id} 写子任务语义事件，造成 {@code EventReconciliationService}
 * 的「末条事件 vs 业务状态」对账误报。故本类与 {@code RuntimeTurnExecutor}
 * <b>并列复用</b> {@code AgentLoop}、{@code ToolRegistry}、{@code VisibleToolCallbacks}
 * 等同一批组件，<b>不</b>改动执行链类的字段与分支（执行链零回归）。</p>
 *
 * <p><b>不写事件流（刻意）</b>：业务面今日（单次链路）本就不产 {@code agent_event}，
 * 其可观测面在 {@code conversation_message} + {@code task_timeline}。循环化<b>不</b>新增
 * 第二条事件通道（plan §10.4 判据 4「事件不双写」）。循环过程事实（轮数 / 工具次数 /
 * 结束原因 / token / 耗时）以 INFO 日志呈现，够用于 E2E 观测。</p>
 *
 * <p><b>回退纪律</b>：开关 {@code helloai.execution.business-loop-enabled} 置 false ⇒
 * {@link #supports} 恒 false ⇒ 全部业务面即时回到裸单次；循环抛异常或返回
 * {@code finishReason=ERROR} ⇒ {@link #runInLoop} 返回 {@code null}，调用方回退单次。
 * 任一路径都<b>不</b>使业务面比改造前更差。</p>
 *
 * <p><b>温度语义边界</b>：{@code AgentTask.temperature} 在循环路径下不生效
 * （{@code AgentLoop} 只吃 {@code ChatModel}，无 options 通道；与执行链现状一致）。
 * 已裁定的 4 个进循环面均未设置温度，故无实际影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessSurfaceLoopRunner {

    /** 本类在执行结果里的执行者标识（区别于单次链路的 {@code ApiKeyAgentExecutor}）。 */
    private static final String EXECUTOR_NAME = "BusinessSurfaceLoopRunner";

    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final AgentMcpServerService agentMcpServerService;
    private final AgentSkillSpecService agentSkillSpecService;
    private final AgentExecutionProperties executionProperties;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final AgentLoop agentLoop;
    private final ToolCallbackProvider toolCallbackProvider;

    /**
     * 内部执行链专用工具（{@code internal_} 前缀）——与执行链同一份可见面（REF-8.1 B）。
     * 非构造注入：既有构造器与单测零改动，缺失时空集合（行为等价）。
     */
    @Autowired(required = false)
    private InternalToolCallbacks internalToolCallbacks;

    /**
     * 本面是否应走工具循环：接入类型为内部 LLM + 循环开关打开 + {@code scene} 命中白名单。
     *
     * @param agent 目标 Agent（须为 {@link AgentAccessType#API_KEY_LLM}）
     * @param task  业务面任务（{@code context.scene} 为其身份）
     * @return true = {@link #runInLoop} 可接管；false = 调用方走原有单次链路
     */
    public boolean supports(Agent agent, AgentTask task) {
        if (agent == null || task == null) {
            return false;
        }
        if (agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            return false;
        }
        AgentExecutionProperties props = executionProperties;
        if (props == null || !props.isBusinessLoopEnabled()) {
            return false;
        }
        String scene = sceneOf(task);
        if (scene == null || scene.isBlank()) {
            return false;
        }
        List<String> whitelist = props.getBusinessLoopScenes();
        return whitelist != null && whitelist.contains(scene);
    }

    /**
     * 以工具循环执行一次业务面调用。
     *
     * <p>装配顺序与执行链同构（模型 → 工具集 → 生效形态 → 循环），差别仅在：无会话 /
     * 无对话流 / 无事件 / 无检查点落库；出口不做「交付物自证」分类。</p>
     *
     * @return 循环结果；<b>{@code null} = 本面应回退单次链路</b>（循环抛异常，或结束原因为 ERROR）
     */
    public AgentResult runInLoop(Agent agent, AgentTask task) {
        String scene = sceneOf(task);
        Long taskId = longOf(task.getContext(), "taskId");
        Long subTaskId = task.getSubTaskId() != null
                ? task.getSubTaskId() : longOf(task.getContext(), "subTaskId");
        long startMs = System.currentTimeMillis();

        AgentLoopResult loopResult;
        try {
            // 1) 底层模型：与执行链同一出口（mock ⇒ MockChatModel；真实 ⇒ 显式 provider + 凭据，
            //    缺失 fail-close；出口已包并发限流装饰器）
            String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
            String apiKey = resolveApiKey(agent);

            // 2) 启用工具集：与执行链同一口径（内部 LLM 走进程内工具通道；
            //    不在此做第二处过滤——可见面的唯一判据是 ToolRegistry.resolve）
            List<String> tools = agentMcpServerService.getEnabledToolsForAccess(
                    agent.getId(), agent.getAccessType());
            List<String> skills = task.getSkills() != null ? task.getSkills() : List.of();
            AgentSkillSpecService.ResolvedSpec resolved = agentSkillSpecService.resolve(skills);
            if (resolved == null) {
                resolved = new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), "");
            }
            List<String> enabledTools = mergeTools(tools, resolved.requiredTools());

            // 3) 生效形态 → 模型可见回调（与执行链共用 VisibleToolCallbacks，防口径漂移）
            ToolContext toolContext = new ToolContext(agent.getId(), taskId, subTaskId,
                    1, agent.getAccessType(), resolved.requiredSkills());
            List<ToolDefinition> resolvedTools = toolRegistry.resolve(enabledTools, toolContext);
            List<ToolCallback> visibleCallbacks = VisibleToolCallbacks.select(resolvedTools,
                    InternalToolCallbacks.union(toolCallbackProvider.getToolCallbacks(), internalToolCallbacks));

            // 4) 循环（内部工具的真实 agentId / subTaskId 带外注入；严格成对解绑防线程复用串味）
            Integer maxIterations = resolveMaxIterations(agent.getAccessType());
            String runId = taskId != null ? AgentEventContextResolver.resolveRunId(taskId) : null;
            // 事件记录器 / 循环检查点回调刻意传 null：业务面不经 agent_event，
            // 亦无 agent_session（会话属子任务执行链），见类 javadoc「不写事件流」。
            ToolInvocationContext.bind(agent.getId(), subTaskId);
            try {
                loopResult = agentLoop.run(new AgentLoopInput(
                        agentChatClientService.buildChatModel(agent, provider, apiKey),
                        task.getSystemPrompt(),
                        task.getUserPrompt(),
                        toolExecutor,
                        visibleCallbacks,
                        maxIterations,
                        runId, taskId, subTaskId, 1, agent.getId(),
                        null, null, agent.getAccessType(),
                        LoopCancellation.of(executionProperties.getTurnDeadlineSeconds(),
                                executionProperties.getToolCallTimeoutMillis())));
            } finally {
                ToolInvocationContext.unbind();
            }
        } catch (AgentCancelledException e) {
            // REF-8.6：取消是独立终态——不回落单次（回落会立刻再跑一遍注定超期的工作），
            // 而是明确判失败并带 finishReason=CANCELLED，由业务面按自己的失败语义处理。
            log.warn("业务面循环被取消: scene={}, agentId={}, taskId={}, reason={}",
                    scene, agent.getId(), taskId, e.getMessage());
            return AgentResult.failure("business_surface_cancelled: " + e.getMessage(),
                    "CANCELLED", EXECUTOR_NAME);
        } catch (Exception e) {
            log.warn("业务面循环执行异常，回退单次链路: scene={}, agentId={}, taskId={}, err={}",
                    scene, agent.getId(), taskId, e.getMessage(), e);
            return null;
        }

        long costMs = System.currentTimeMillis() - startMs;
        if (!loopResult.success() && "ERROR".equals(loopResult.finishReason())) {
            log.warn("业务面循环异常终态，回退单次链路: scene={}, agentId={}, taskId={}, err={}, costMs={}",
                    scene, agent.getId(), taskId, loopResult.errorMessage(), costMs);
            return null;
        }
        log.info("业务面循环完成: scene={}, agentId={}, taskId={}, subTaskId={}, iterations={}, "
                        + "toolCalls={}, finishReason={}, tokens={}, costMs={}",
                scene, agent.getId(), taskId, subTaskId, loopResult.iterations(),
                loopResult.toolCallCount(), loopResult.finishReason(), loopResult.tokenUsage(), costMs);

        // MAX_ITERATIONS：携带末轮正文（AgentLoopResult 的契约），判成功让业务面自行解析 /
        // 兜底——不静默丢弃已发生的轮次产出；finishReason 原样透出（真值不掩盖）。
        return AgentResult.success(
                loopResult.text() != null ? loopResult.text() : "",
                loopResult.thinking(),
                loopResult.finishReason(),
                EXECUTOR_NAME,
                loopResult.tokenUsage());
    }

    /** 读 {@code scene}（{@code AgentTask.context} 的身份键）。 */
    private static String sceneOf(AgentTask task) {
        Object scene = task.getContext() != null ? task.getContext().get("scene") : null;
        return scene instanceof String s ? s : null;
    }

    /** 读 context 中的 Long 型标识（缺失 / 类型不符 ⇒ null）。 */
    private static Long longOf(Map<String, Object> context, String key) {
        Object value = context != null ? context.get(key) : null;
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 解析并校验 API Key（与执行链同一 fail-close 口径）：mock 模式返回 null；
     * 真实模式取平台级 / Agent 级凭据，开启 {@code requireVault} 且无凭据时抛 {@link BizException}。
     */
    private String resolveApiKey(Agent agent) {
        if (executionProperties.isMockMode()) {
            return null;
        }
        String apiKey = agentLlmCredentialResolver.resolveApiKey(agent);
        if (executionProperties.isRequireVault() && (apiKey == null || apiKey.isBlank())) {
            throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId()
                    + "，请先在系统管理中配置模型 API Key");
        }
        return apiKey;
    }

    /**
     * 循环轮数档位：与执行链 {@code RuntimeTurnExecutor#resolveMaxIterations} 同口径
     * （内部 LLM 档位默认 10；配置缺失 / 非正回落硬默认）。
     */
    private Integer resolveMaxIterations(AgentAccessType accessType) {
        boolean internal = accessType == AgentAccessType.API_KEY_LLM;
        AgentExecutionProperties props = executionProperties;
        if (props == null) {
            return internal ? AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA
                    : AgentLoopInput.DEFAULT_MAX_ITERATIONS;
        }
        int configured = internal ? props.getInternalMaxIterations() : props.getExternalMaxIterations();
        return configured > 0 ? configured
                : (internal ? AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA
                : AgentLoopInput.DEFAULT_MAX_ITERATIONS);
    }

    /** 启用工具并集（上下文工具 ∪ 命中技能 requiredTools）：委托单源 {@link EnabledToolNames}。 */
    private static List<String> mergeTools(List<String> tools, List<String> skillRequiredTools) {
        return EnabledToolNames.union(tools, skillRequiredTools);
    }
}
