package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 原生联网搜索实现（把 DeepSeek 当"搜索引擎"用，V42）。
 *
 * <p>走 DeepSeek Anthropic 兼容端点（{@code /anthropic/v1/messages}）+
 * {@code web_search_20250305} 服务端工具：单次调用「Perform a web search for query: X」，
 * 服务端执行检索后在响应 content 中返回结构化 {@code web_search_tool_result} 块
 * （title / url / 引用原文），本实现只解析该块映射为 {@link WebSearchResult} 列表，
 * 模型生成的正文属于副产品（token 上限已由配置压低），不消费。</p>
 *
 * <p>选型理由：结构化结果（查询词 / 来源明细）可完整落 payload 供查验条渲染，
 * 不切断可视化链路；相比博查/Tavily 省去独立搜索服务订阅，复用 DeepSeek API Key。
 * 注意该路径是一次完整 LLM 调用，耗时与成本高于普通搜索 API，超时独立配置。</p>
 *
 * <p>失败语义：捕获所有异常返回空列表，不抛出（{@link WebSearchService} 契约）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "helloai.web-search.provider", havingValue = "deepseek-native")
public class DeepSeekNativeSearchServiceImpl implements WebSearchService {

    /** Anthropic 协议版本头（Anthropic 兼容端点必填）。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 服务端工具类型标识（Anthropic 命名约定：web_search + 发布日期后缀）。 */
    private static final String WEB_SEARCH_TOOL_TYPE = "web_search_20250305";

    /** 响应中搜索结果块的 type。 */
    private static final String BLOCK_WEB_SEARCH_RESULT = "web_search_tool_result";

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public DeepSeekNativeSearchServiceImpl(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getDeepseekTimeoutMs()))
                .build();
    }

    @Override
    public String provider() { return "deepseek-native"; }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        if (properties.getDeepseekApiKey() == null || properties.getDeepseekApiKey().isBlank()) {
            log.warn("DeepSeek API Key 未配置（helloai.web-search.deepseek-api-key），跳过本次搜索");
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        int limit = Math.max(1, Math.min(maxResults, properties.getMaxResults()));
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getDeepseekModel(),
                    "max_tokens", properties.getDeepseekMaxTokens(),
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", "Perform a web search for query: " + query
                    )),
                    "tools", List.of(Map.of(
                            "type", WEB_SEARCH_TOOL_TYPE,
                            "name", "web_search",
                            "max_uses", 1
                    ))
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getDeepseekBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getDeepseekTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", properties.getDeepseekApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("DeepSeek 原生搜索返回非 2xx: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));
                return List.of();
            }
            return parseResponse(response.body(), limit);
        } catch (Exception e) {
            log.warn("DeepSeek 原生搜索失败（已降级为空列表）: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Anthropic 格式响应：{@code content} 为内容块数组，按执行顺序含
     * {@code server_tool_use}（服务端发起检索）/ {@code web_search_tool_result}
     * （结构化结果，其 {@code content} 为 web_search_result 列表：
     * title / url / content（引用原文）/ page_age 等）/ {@code text}（最终正文，不消费）。
     * 取首个 {@code web_search_tool_result} 块映射，最多 {@code limit} 条。
     */
    private List<WebSearchResult> parseResponse(String body, int limit) {
        List<WebSearchResult> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode blocks = root.path("content");
            if (!blocks.isArray()) return out;
            int maxSnippet = properties.getMaxSnippetChars();
            for (JsonNode block : blocks) {
                if (!BLOCK_WEB_SEARCH_RESULT.equals(textOrNull(block.path("type")))) continue;
                JsonNode results = block.path("content");
                if (!results.isArray()) continue;
                for (JsonNode r : results) {
                    if (out.size() >= limit) break;
                    String title = textOrNull(r.path("title"));
                    String url = textOrNull(r.path("url"));
                    // 引用原文字段名以 content 为准，缺则回退 snippet（不同版本兼容）
                    String raw = textOrNull(r.path("content"));
                    if (raw == null) raw = textOrNull(r.path("snippet"));
                    if (raw == null) continue;
                    out.add(WebSearchResult.builder()
                            .title(safe(title, "(无标题)"))
                            .url(safe(url, ""))
                            .snippet(truncate(raw, maxSnippet))
                            .siteName(hostOf(url))
                            .build());
                }
                break; // 只取首个结果块（max_uses=1，正常也只有一次检索）
            }
        } catch (Exception e) {
            log.warn("DeepSeek 原生搜索响应解析失败: err={}", e.getMessage());
            return out;
        }
        return out;
    }

    /** 从 URL 提取 host 作为站点名（响应不带 siteName 字段）。 */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String safe(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
