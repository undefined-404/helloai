package com.helloai.core.planner.tool;

import com.helloai.core.planner.search.WebModalCard;
import com.helloai.core.planner.search.WebSearchResult;

import java.util.List;

/**
 * web_search 平台工具归一化输出（spring-ai @Tool 返回对象，序列化为 JSON）。
 *
 * <p>承载一次联网搜索的结果快照：供应商标识 + 结果列表（供应商归一）+ 可选的大模型
 * 总结（{@code withAnswer=true} 时供应商支持才返回）+ 可选的结构化模态卡（W-7，
 * 博查 AI Search 的垂域卡）。消费方 {@code ClarifyWebSearchOrchestrator} 按本结构
 * 反序列化工具输出；字段缺失容忍（compact constructor 兜底），保持「搜索失败/缺字段
 * 降级空结果」契约。</p>
 *
 * <p><b>向后兼容</b>：{@code modalCards} 是 W-7 新增组件，旧工具输出 JSON（无该键）
 * 反序列化时传 null ⇒ compact constructor 兜底为 {@code List.of()}，不破坏旧 payload。</p>
 *
 * @param provider   供应商标识（如 bocha / tavily；工具层失败时为占位）
 * @param results    归一化结果列表（永不为 null，可为空）
 * @param answer     供应商大模型总结（按需请求；不支持/空时为 null）
 * @param modalCards 结构化模态卡（W-7；不支持/无卡时为空列表，永不为 null）
 */
public record WebSearchToolResult(String provider, List<WebSearchResult> results,
                                  String answer, List<WebModalCard> modalCards) {

    public WebSearchToolResult {
        provider = provider == null ? "" : provider;
        results = results == null ? List.of() : List.copyOf(results);
        modalCards = modalCards == null ? List.of() : List.copyOf(modalCards);
    }

    /** 空结果（工具层失败/查询缺失的降级表达，不抛异常）。 */
    public static WebSearchToolResult empty(String provider) {
        return new WebSearchToolResult(provider, List.of(), null, List.of());
    }
}
