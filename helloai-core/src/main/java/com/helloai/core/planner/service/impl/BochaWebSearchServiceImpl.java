package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebSearchCredentialKeyStore;
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
import java.util.LinkedHashMap;
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
 *
 * <p>API Key 解析统一走 {@link WebSearchCredentialKeyStore}（sys_config 加密值 >
 * yml/env 兜底，系统设置页可写），不再直读 properties 避免占位符字面量误用。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "helloai.web-search.provider", havingValue = "bocha", matchIfMissing = true)
public class BochaWebSearchServiceImpl implements WebSearchService {

    private final WebSearchProperties properties;
    private final WebSearchCredentialKeyStore credentialKeyStore;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BochaWebSearchServiceImpl(WebSearchProperties properties,
                                     WebSearchCredentialKeyStore credentialKeyStore,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.credentialKeyStore = credentialKeyStore;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    @Override
    public String provider() { return "bocha"; }

    /**
     * 验证博查 API Key：发送最小搜索请求（query=ping、summary=false、count=1），
     * 校验 HTTP 2xx 且响应 {@code code=200}。Key 未配置时直接返回失败，不发起请求。
     */
    @Override
    public Map<String, Object> verifyApiKey() {
        long start = System.currentTimeMillis();
        String apiKey = credentialKeyStore.resolveBochaApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return verifyResult(false, "尚未配置博查 API Key，请先保存密钥再验证", start);
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", "ping",
                    "summary", false,
                    "count", 1,
                    "freshness", "noLimit"
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBochaBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                boolean authFail = response.statusCode() == 401 || response.statusCode() == 403;
                log.warn("博查 Key 验证返回非 2xx: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));
                return verifyResult(false,
                        (authFail ? "API Key 无效或无权限" : "验证失败（HTTP " + response.statusCode() + "）")
                                + "：" + truncate(response.body(), 120), start);
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code = root.path("code").asInt(-1);
            if (code != 200) {
                String msg = textOrNull(root.path("msg"));
                return verifyResult(false, "验证失败（博查 code=" + code + "）"
                        + (msg != null ? "：" + truncate(msg, 120) : ""), start);
            }
            long elapsed = System.currentTimeMillis() - start;
            return verifyResult(true, "验证通过，博查 API Key 有效（" + elapsed + "ms）", start);
        } catch (Exception e) {
            log.warn("博查 Key 验证异常（已降级为失败结果）: err={}", e.getMessage());
            return verifyResult(false, "验证请求失败：" + e.getMessage(), start);
        }
    }

    private static Map<String, Object> verifyResult(boolean success, String message, long start) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success);
        out.put("supported", true);
        out.put("message", message);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        String apiKey = credentialKeyStore.resolveBochaApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("博查 API Key 未配置（系统设置页「联网搜索」或 env BOCHA_API_KEY），跳过本次搜索");
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
                    .header("Authorization", "Bearer " + apiKey)
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
