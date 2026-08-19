package com.helloai.core.planner.service;

import com.helloai.core.planner.search.WebSearchResult;

import java.util.List;

/**
 * 联网搜索服务接口（供应商无关）。
 *
 * <p>实现类：{@link BochaWebSearchServiceImpl} / {@link TavilyWebSearchServiceImpl}
 * / {@link DeepSeekNativeSearchServiceImpl}（DeepSeek 原生 web_search 服务端工具），
 * 由 Spring 条件装配（{@code @ConditionalOnProperty}）按配置项
 * {@code helloai.web-search.provider} 自动激活其中一个。</p>
 *
 * <p>失败语义：调用方实现必须捕获所有异常返回空列表，<b>不</b>抛出。
 * 目的：搜索是辅助增强，失败时不能阻断需求澄清主流程。</p>
 */
public interface WebSearchService {

    /**
     * 搜索并返回归一化的结果列表。
     *
     * @param query     搜索关键词（已由调用方提取/改写）
     * @param maxResults 最大结果条数（实现侧应进一步截断 snippet）
     * @return 结果列表，失败时返回空列表（绝不抛异常）
     */
    List<WebSearchResult> search(String query, int maxResults);

    /**
     * 供应商名（用于日志/调试，例如 "bocha" / "tavily"）。
     */
    String provider();
}
