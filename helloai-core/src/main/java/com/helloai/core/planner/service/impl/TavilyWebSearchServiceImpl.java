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
 * Tavily Search API 实现（境外供应商，备用）。
 *
 * <p>API 文档：{@code https://docs.tavily.com/docs/rest-api/api-reference}
 * 请求体：{@code {"api_key":"...", "query":"...", "max_results":5, "include_answer":false}}
 * 响应：{@code {"results":[{title, url, content, score, ...}], "answer": "..."}</p>
 *
 * <p>失败语义：捕获所有异常返回空列表，不抛出（{@link WebSearchService} 契约）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "helloai.web-search.provider", havingValue = "tavily")
public class TavilyWebSearchServiceImpl implements WebSearchService {

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TavilyWebSearchServiceImpl(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    @Override
    public String provider() { return "tavily"; }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        if (properties.getTavilyApiKey() == null || properties.getTavilyApiKey().isBlank()) {
            log.warn("Tavily API Key 未配置（helloai.web-search.tavily-api-key），跳过本次搜索");
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        int limit = Math.max(1, Math.min(maxResults, properties.getMaxResults()));
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "api_key", properties.getTavilyApiKey(),
                    "query", query,
                    "max_results", limit,
                    "include_answer", false,
                    "search_depth", "basic"
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getTavilyBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("Tavily 搜索返回非 2xx: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));
                return List.of();
            }
            return parseResponse(response.body(), limit);
        } catch (Exception e) {
            log.warn("Tavily 搜索失败（已降级为空列表）: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    private List<WebSearchResult> parseResponse(String body, int limit) {
        List<WebSearchResult> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray()) return out;
            int maxSnippet = properties.getMaxSnippetChars();
            for (JsonNode r : results) {
                if (out.size() >= limit) break;
                String title = textOrNull(r.path("title"));
                String url = textOrNull(r.path("url"));
                String raw = textOrNull(r.path("content"));
                if (raw == null) continue;
                out.add(WebSearchResult.builder()
                        .title(safe(title, "(无标题)"))
                        .url(safe(url, ""))
                        .snippet(truncate(raw, maxSnippet))
                        .siteName(null) // Tavily 不返回 siteName
                        .build());
            }
        } catch (Exception e) {
            log.warn("Tavily 响应解析失败: err={}", e.getMessage());
            return out;
        }
        return out;
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
