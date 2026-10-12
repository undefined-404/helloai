package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.InternalToolCallbacks;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ToolExecutor} 真身——懒加载 spring-ai {@link ToolCallback} 目录并按名调用。
 *
 * <p>与 {@link ToolRegistryImpl} 同源同构（同一 {@link ToolCallbackProvider}，单一事实源），
 * 但职责相反：Registry 只认识工具（元数据），本类真正执行工具（{@code callback.call}）。
 * 懒加载策略沿用 Registry 已验证模式：首次 execute 触发、失败降级为空目录（不阻断调用方）、
 * 目录缓存复用。</p>
 *
 * <p>鉴权口径（与 {@code McpAuthContext} 一致）：业务 MCP 工具所需的 {@code agentId / _sessionId}
 * 等 @ToolParam 由调用方在 {@code argumentsJson} 中显式传入（鉴权透传口径）；
 * echo 等无鉴权工具可直接调用。<b>例外（REF-8.1 A1）</b>：{@code internal_} 前缀的进程内工具，
 * 其 {@code agentId / subTaskId} 由执行链经 {@link ToolInvocationContext} 带外注入
 * （模型无从自报），注入后覆盖模型所给值。执行过程不发事件（事件 write-only 纪律，由调用方按
 * TOOL_CALL_STARTED / TOOL_CALL_COMPLETED 槽位记录）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallbackToolExecutor implements ToolExecutor {

    private final ToolCallbackProvider toolCallbackProvider;

    /**
     * 内部执行链专用工具（REF-8.1 B，{@code internal_} 前缀）——不进对外 MCP {@code tools/list}。
     * 非构造注入：既有单参构造器与单测零改动，缺失时空集合（行为与合并前等价）。
     */
    @Autowired(required = false)
    private InternalToolCallbacks internalToolCallbacks;

    /** 已注册工具名 → ToolCallback（LinkedHashMap 保序：MCP 工具注册顺序）；null = 尚未加载。 */
    private volatile Map<String, ToolCallback> catalog;

    @Override
    public ToolExecutionResult execute(String toolName, String argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return ToolExecutionResult.failure(toolName, "tool name is blank");
        }
        Map<String, ToolCallback> current = ensureCatalog();
        ToolCallback callback = current.get(toolName);
        if (callback == null) {
            return ToolExecutionResult.failure(toolName, "unknown tool: " + toolName);
        }
        String input = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        // REF-8.1 A1：内部工具标识带外注入——agentId / subTaskId 不入模型上下文，由执行链
        // （RuntimeTurnExecutor → ToolInvocationContext）供给。仅 internal_ 前缀 + 工具已声明字段生效，
        // 外部 MCP 工具逐字不变（口径见 ToolInvocationContext）。
        ToolDefinition definition = callback.getToolDefinition();
        input = ToolInvocationContext.inject(
                definition != null ? definition.name() : null,
                definition != null ? definition.inputSchema() : null,
                input);
        try {
            String output = callback.call(input);
            log.debug("ToolExecutor: tool={} 执行成功", toolName);
            return ToolExecutionResult.success(toolName, output);
        } catch (Exception e) {
            log.warn("ToolExecutor: tool={} 执行失败: err={}", toolName, e.getMessage());
            return ToolExecutionResult.failure(toolName, e.getMessage());
        }
    }

    /**
     * 懒加载工具执行目录（首次 execute 触发，之后复用缓存）。
     *
     * <p>加载失败降级为「空目录 + 不再重试」（catalog 赋空 Map），execute 恒按 unknown tool
     * 失败返回，不阻断调用方；与 {@link ToolRegistryImpl#ensureCatalog} 同款防御。</p>
     */
    private Map<String, ToolCallback> ensureCatalog() {
        Map<String, ToolCallback> current = catalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (catalog != null) {
                return catalog;
            }
            Map<String, ToolCallback> built = new LinkedHashMap<>();
            try {
                // REF-8.1 B：执行目录 = 对外 MCP 面 ∪ 内部执行链专用面（internal_ 前缀在后）
                ToolCallback[] callbacks = InternalToolCallbacks.union(
                        toolCallbackProvider.getToolCallbacks(), internalToolCallbacks);
                if (callbacks != null) {
                    for (ToolCallback callback : callbacks) {
                        if (callback == null || callback.getToolDefinition() == null) {
                            continue;
                        }
                        String name = callback.getToolDefinition().name();
                        if (name != null && !name.isBlank()) {
                            built.put(name, callback);
                        }
                    }
                }
                log.info("ToolExecutor: 工具执行目录加载完成，共 {} 个工具", built.size());
            } catch (Exception e) {
                log.warn("ToolExecutor: 工具执行目录加载失败（目录为空，execute 恒失败，不阻断调用方）: err={}",
                        e.getMessage());
            }
            catalog = built;
            return catalog;
        }
    }
}
