package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
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
 * echo 等无鉴权工具可直接调用。执行过程不发事件（事件 write-only 纪律，由调用方按
 * TOOL_CALL_STARTED / TOOL_CALL_COMPLETED 槽位记录）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallbackToolExecutor implements ToolExecutor {

    private final ToolCallbackProvider toolCallbackProvider;

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
                ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
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
