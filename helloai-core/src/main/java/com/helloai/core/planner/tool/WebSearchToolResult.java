package com.helloai.core.planner.tool;

import com.helloai.core.planner.search.WebSearchResult;

import java.util.List;

/**
 * web_search 平台工具归一化输出（spring-ai @Tool 返回对象，序列化为 JSON）。
 *
 * <p>承载一次联网搜索的结果快照：供应商标识 + 结果列表（供应商归一）+ 可选的大模型
 * 总结（{@code withAnswer=true} 时供应商支持才返回）。消费方
 * {@code ClarifyWebSearchOrchestrator} 按本结构反序列化工具输出；字段缺失容忍
 * （compact constructor 兜底），保持「搜索失败/缺字段降级空结果」契约。</p>
 *
 * @param provider 供应商标识（如 bocha / tavily；工具层失败时为占位）
 * @param results  归一化结果列表（永不为 null，可为空）
 * @param answer   供应商大模型总结（按需请求；不支持/空时为 null）
 */
public record WebSearchToolResult(String provider, List<WebSearchResult> results, String answer) {

    public WebSearchToolResult {
        provider = provider == null ? "" : provider;
        results = results == null ? List.of() : List.copyOf(results);
    }

    /** 空结果（工具层失败/查询缺失的降级表达，不抛异常）。 */
    public static WebSearchToolResult empty(String provider) {
        return new WebSearchToolResult(provider, List.of(), null);
    }
}