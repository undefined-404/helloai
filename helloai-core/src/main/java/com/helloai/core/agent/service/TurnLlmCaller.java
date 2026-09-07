package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turn LLM 调用器（P0-B-2 主链接线注入点）。
 *
 * <p>封装 executeOnce LLM 调用点的 Legacy ↔ Runtime 切换（同一 {@code runtime-enabled}
 * 开关，默认 false=Legacy）：</p>
 * <ul>
 *   <li>Legacy：委托 {@link PlatformAgentExecutionService#executeSync}（单次 LLM 调用；
 *       TOOL_CALL 标记由 executeOnce 围绕调用记录，本组件不记录）；</li>
 *   <li>Runtime：AgentLoop 手动工具循环——ChatModel 经工厂出口（{@code buildChatModel}）
 *       构建 + ToolExecutor 执行工具 + 循环内部记录 TOOL_CALL 事件（executeOnce 不再
 *       手动记录，防双记）。</li>
 * </ul>
 *
 * <p>失败语义：Runtime 循环异常转 {@link AgentResult#failure}（best-effort 不抛）。
 * 真实 provider 的 tool-calling 行为需在启用开关的 dev 环境联调（本组件以 mock ChatModel
 * 单测验证切换与映射逻辑）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnLlmCaller {

    private final AgentExecutionProperties executionProperties;
    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final AgentLoop agentLoop;
    private final ToolExecutor toolExecutor;
    private final ToolCallbackProvider toolCallbackProvider;

    /** Runtime 开关（executeOnce 用于条件记录 TOOL_CALL 标记，防双记）。 */
    public boolean isRuntimeEnabled() {
        return executionProperties.isRuntimeEnabled();
    }

    /**
     * 调用一次 Turn 的 LLM（按 {@code runtime-enabled} 切换 Legacy 单次 / Runtime 循环）。
     */
    public AgentResult call(TurnLlmCallContext ctx) {
        if (!executionProperties.isRuntimeEnabled()) {
            return platformAgentExecutionService.executeSync(ctx.agent(), ctx.task());
        }
        return runRuntimeLoop(ctx);
    }

    private AgentResult runRuntimeLoop(TurnLlmCallContext ctx) {
        try {
            String provider = AgentProviderResolver.resolveProvider(ctx.agent(), executionProperties.getProvider());
            String apiKey = resolveApiKey(ctx.agent());
            ChatModel chatModel = agentChatClientService.buildChatModel(ctx.agent(), provider, apiKey);
            AgentLoopResult loop = agentLoop.run(new AgentLoopInput(
                    chatModel, "", ctx.task().getUserPrompt(), toolExecutor,
                    resolveEnabledCallbacks(ctx.tools()), null,
                    ctx.runId(), ctx.taskId(), ctx.subTaskId(), ctx.turn(),
                    ctx.agent().getId(), ctx.eventRecorder()));
            if (loop.success()) {
                return AgentResult.success(loop.text(), loop.thinking(), loop.finishReason(), "RuntimeAgentLoop", null);
            }
            return AgentResult.failure(loop.errorMessage() != null ? loop.errorMessage() : loop.text(),
                    loop.finishReason(), "RuntimeAgentLoop");
        } catch (Exception e) {
            log.warn("TurnLlmCaller: Runtime 循环异常（转失败结果）: subTaskId={}, err={}",
                    ctx.subTaskId(), e.getMessage());
            return AgentResult.failure(e.getMessage(), "ERROR", "RuntimeAgentLoop");
        }
    }

    /** mock 模式返回 null；真实模式解析 Vault/Agent 级 API Key（requireVault 校验同 ApiKeyAgentExecutor 口径）。 */
    private String resolveApiKey(Agent agent) {
        if (executionProperties.isMockMode()) {
            return null;
        }
        String apiKey = agentLlmCredentialResolver.resolveApiKey(agent);
        if (executionProperties.isRequireVault()
                && (apiKey == null || apiKey.isBlank())) {
            throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId());
        }
        return apiKey;
    }

    /** 按启用工具名过滤 ToolCallback 目录（循环可见 schema 与可调工具一致；best-effort）。 */
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
            log.warn("TurnLlmCaller: 工具回调解析失败（循环内无工具）: err={}", e.getMessage());
        }
        return callbacks;
    }
}
