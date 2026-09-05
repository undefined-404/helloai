package com.helloai.core.agent.tool.impl;

import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ToolRegistry} 实现——从 spring-ai {@link ToolCallbackProvider}（McpToolConfig
 * 注册的全部 @Tool，单一事实源）收集平台工具元数据目录。
 *
 * <p>Phase 1 Step 2：平台当前工具均为 MCP 工具（McpMcpServer 11 + EchoMcpTool 1），
 * 目录自动跟随 MCP 工具注册，零漂移；未来 GitTool / ShellTool 等工具类型（长期思路
 * P1）注册进同一目录即可，不另建平行 Registry（§50.7）。</p>
 *
 * <p>懒加载（首次 resolve 触发）：避免装配期反射失败阻断启动；目录为空/加载失败时
 * resolve 恒返回空列表（事件 write-only，不阻断执行链），日志可观测。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistryImpl implements ToolRegistry {

    private final ToolCallbackProvider toolCallbackProvider;

    /** 已注册工具名 → 元数据（LinkedHashMap 保序：MCP 工具注册顺序）；null = 尚未加载。 */
    private volatile Map<String, ToolDefinition> catalog;

    @Override
    public List<ToolDefinition> resolve(List<String> enabledToolNames) {
        if (enabledToolNames == null || enabledToolNames.isEmpty()) {
            return List.of();
        }
        Map<String, ToolDefinition> current = ensureCatalog();
        List<ToolDefinition> resolved = new ArrayList<>();
        for (String name : enabledToolNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            ToolDefinition definition = current.get(name);
            if (definition != null) {
                resolved.add(definition);
            }
        }
        return resolved;
    }

    /**
     * 懒加载工具目录（首次 resolve 触发，之后复用缓存）。
     *
     * <p>加载失败降级为「空目录 + 不再重试」（catalog 赋空 Map，避免每次调用都反射），
     * resolve 恒返回空列表，不阻断执行链；目录为空不影响 AgentContext.tools 名称列表
     * 的显式供电（名称来自 agent_mcp_server，与元数据目录无关）。</p>
     */
    private Map<String, ToolDefinition> ensureCatalog() {
        Map<String, ToolDefinition> current = catalog;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (catalog != null) {
                return catalog;
            }
            Map<String, ToolDefinition> built = new LinkedHashMap<>();
            try {
                ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
                if (callbacks != null) {
                    for (ToolCallback callback : callbacks) {
                        if (callback == null || callback.getToolDefinition() == null) {
                            continue;
                        }
                        String name = callback.getToolDefinition().name();
                        String description = callback.getToolDefinition().description();
                        if (name == null || name.isBlank()) {
                            continue;
                        }
                        built.put(name, new ToolDefinition(name, description));
                    }
                }
                log.info("ToolRegistry: 工具目录加载完成，共 {} 个工具", built.size());
            } catch (Exception e) {
                log.warn("ToolRegistry: 工具目录加载失败（目录为空，resolve 恒空，不阻断执行链）: err={}",
                        e.getMessage());
            }
            catalog = built;
            return catalog;
        }
    }
}
