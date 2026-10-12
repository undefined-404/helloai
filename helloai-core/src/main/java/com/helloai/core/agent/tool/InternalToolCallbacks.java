package com.helloai.core.agent.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 内部执行链<b>专用</b>工具回调集合（REF-8.1 B，2026-10-12）。
 *
 * <p><b>为什么单独一类</b>：{@code internal_} 前缀的进程内工具（{@link
 * com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools}）只应被<b>内部执行链</b>消费，
 * <b>不应</b>出现在对外 MCP {@code tools/list} 上。而 spring-ai 1.1.8 的 MCP Server 自动配置
 * （{@code ToolCallbackConverterAutoConfiguration#aggregateToolCallbacks}）会<b>聚合全部</b>
 * {@code ToolCallbackProvider} / {@code ToolCallback} Bean 并转为 {@code SyncToolSpecification}——
 * 因此只要内部工具存在于任一 {@code ToolCallbackProvider} Bean 内，就必然对外暴露。</p>
 *
 * <p><b>解法</b>：{@link com.helloai.core.agent.mcp.McpToolConfig#mcpToolCallbacks()} 只注册
 * <b>对外面</b>（{@code echo + McpMcpServer + contributors}），内部工具改由本类（<b>不是</b>
 * {@code ToolCallbackProvider}，故不被自动配置收集）承载；运行时经 {@link #union} 把两面合成
 * 一份目录供 {@code ToolRegistry} / {@code ToolCallbackToolExecutor} / {@code RuntimeTurnExecutor}
 * 消费。二者仍同源出自 {@code McpToolConfig}，无第二事实源、无平行通道。</p>
 *
 * <p>「对外匿名」不是安全边界本身，而是契约边界；安全兜底另有一道——内部工具入口的
 * 「执行链绑定」强制闸门（见 {@code InternalLlmExecutorTools#requireInternalChain()}）。</p>
 */
public final class InternalToolCallbacks {

    private final ToolCallback[] callbacks;

    public InternalToolCallbacks(ToolCallback[] callbacks) {
        this.callbacks = callbacks != null ? callbacks.clone() : new ToolCallback[0];
    }

    /** 内部执行链专用回调（防御性拷贝；永不为 null）。 */
    public ToolCallback[] getToolCallbacks() {
        return callbacks.clone();
    }

    /**
     * 运行时工具目录 = 对外 MCP 面 ∪ 内部执行链专用面。
     *
     * <p>纯函数、无副作用；任一入参为 null 均按空集合处理（单测 / 无内部工具场景下
     * 行为与合并前逐字等价）。</p>
     *
     * @param external 对外 MCP 面（{@code ToolCallbackProvider.getToolCallbacks()}）
     * @param internal 内部执行链专用面（可为 null）
     * @return 合并后的回调数组（过滤 null 元素）
     */
    public static ToolCallback[] union(ToolCallback[] external, InternalToolCallbacks internal) {
        ToolCallback[] internalCallbacks = internal != null ? internal.callbacks : null;
        int externalLen = external != null ? external.length : 0;
        int internalLen = internalCallbacks != null ? internalCallbacks.length : 0;
        if (internalLen == 0) {
            return external != null ? external : new ToolCallback[0];
        }
        List<ToolCallback> merged = new ArrayList<>(externalLen + internalLen);
        appendNonNull(merged, external);
        appendNonNull(merged, internalCallbacks);
        return merged.toArray(new ToolCallback[0]);
    }

    private static void appendNonNull(List<ToolCallback> target, ToolCallback[] source) {
        if (source == null) {
            return;
        }
        for (ToolCallback callback : source) {
            if (callback != null) {
                target.add(callback);
            }
        }
    }
}
