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
 * 博查 Web Search API 实现（国内首选供应商）。
 *
 * <p>API 文档：{@code https://api.bochaai.com/v1/web-search}
 * 请求体：{@code {"query": "...", "summary": true, "count": 5, "freshness": "noLimit"}}
 * <code>summary=true</code> 让博查返回 AI 摘要，省去自解析网页的负担。</p>
 *
 * <p>失败语义：捕获所有异常返回空列表，不抛出（{@link WebSearchService} 契约）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "helloai.web-search.provider", havingValue = "bocha", matchIfMissing = true)
public class BochaWebSearchServiceImpl implements WebSearchService {

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BochaWebSearchServiceImpl(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    @Override
    public String provider() { return "bocha"; }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        if (properties.getBochaApiKey() == null || properties.getBochaApiKey().isBlank()) {
            log.warn("博查 API Key 未配置（helloai.web-search.bocha-api-key），跳过本次搜索");
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        int limit = Math.max(1, Math.min(maxResults, properties.getMaxResults()));
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "summary", true,
                    "count", limit,
                    "freshness", "noLimit"
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBochaBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getBochaApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("博查搜索返回非 2xx: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));
                return List.of();
            }
            return parseResponse(response.body(), limit);
        } catch (Exception e) {
            log.warn("博查搜索失败（已降级为空列表）: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 博查响应：{@code {"code":200, "data":{"webPages":{"value":[{...}], "totalEstimatedMatches":...}}},
     * "msg":null}}。每条 value 含 name/url/snippet/siteName/datePublished/summary 等字段。
     */
    private List<WebSearchResult> parseResponse(String body, int limit) {
        List<WebSearchResult> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode values = root.path("data").path("webPages").path("value");
            if (!values.isArray()) return out;
            int maxSnippet = properties.getMaxSnippetChars();
            for (JsonNode v : values) {
                if (out.size() >= limit) break;
                String title = textOrNull(v.path("name"));
                String url = textOrNull(v.path("url"));
                // 优先用 summary（AI 摘要），缺则回退 snippet
                String raw = textOrNull(v.path("summary"));
                if (raw == null) raw = textOrNull(v.path("snippet"));
                if (raw == null) continue;
                out.add(WebSearchResult.builder()
                        .title(safe(title, "(无标题)"))
                        .url(safe(url, ""))
                        .snippet(truncate(raw, maxSnippet))
                        .siteName(textOrNull(v.path("siteName")))
                        .build());
            }
        } catch (Exception e) {
            log.warn("博查响应解析失败: err={}", e.getMessage());
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
