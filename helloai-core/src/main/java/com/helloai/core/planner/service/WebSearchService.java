package com.helloai.core.planner.service;

import com.helloai.core.planner.search.WebSearchResult;

import java.util.List;
import java.util.Map;

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

    /**
     * 获取最近一次 {@link #search} 的大模型总结答案（供应商能力扩展）。
     *
     * <p>仅支持大模型总结的供应商（博查 AI Search {@code answer=true}）覆盖返回；
     * 不支持返回 null。调用方（搜索编排）在 {@code search} 之后取本次结果附带的
     * 总结文本，注入 Prompt 对齐「搜索后给出总结」的网页版体验。
     * 语义约束：与 {@link #search} 配对使用（search 先行），非配对调用返回 null/旧值。</p>
     *
     * @param query 与最近一次 {@link #search} 相同的查询词（实现侧可不使用，仅语义锚定）
     * @return 本次搜索结果的大模型总结文本；供应商不支持返回 null
     */
    default String answerSummary(String query) {
        return null;
    }

    /**
     * 验证当前供应商 API Key 是否有效（系统设置页保存密钥后调用）。
     *
     * <p>实现侧应发送最小探测请求（如 count=1 的搜索）。默认实现返回不支持；
     * 失败不抛异常，收敛为 {@code success=false} + 可读 message。</p>
     *
     * @return 验证结果：success（Boolean）/ message（String）/ supported（Boolean）/ elapsedMs（Long）
     */
    default Map<String, Object> verifyApiKey() {
        return Map.of("success", false, "supported", false,
                "message", "当前搜索供应商不支持在线验证，请通过实际搜索确认密钥有效");
    }
}
