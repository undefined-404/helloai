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
 * 博查 AI Search API 实现（高级搜索，最接近 Kimi / DeepSeek 网页版检索体验）。
 *
 * <p>API 文档：{@code https://api.bochaai.com/v1/ai-search}
 * 请求体：{@code {"query": "...", "freshness": "noLimit", "count": N, "answer": false, "stream": false}}
 * <code>answer=false</code>：不启用博查侧大模型总结（避免与澄清主回复 LLM 职责重复、省额外成本），
 * 只取内置 Query 改写后的高容量检索结果（单次最多 50 条）+ 多模态卡（当前仅消费网页结果）。
 * <code>stream=false</code>：同步 JSON 返回（搜索是澄清轮前置预检索，不走流式链路）。</p>
 *
 * <p>响应结构（非流式）：AI Search 与 Web Search 不同——网页结果位于
 * {@code messages[]（type=source / content_type=webpage）→ content（对象或 JSON 字符串）→ value[]}；
 * 同时兼容 Web Search 的 {@code data.webPages.value} 路径（防 API 形态演进）。</p>
 *
 * <p>复用博查 API Key（{@link WebSearchCredentialKeyStore}，与基础 Web Search 同一密钥）。
 * 结果条数走 provider 级 {@link WebSearchProperties#getAiSearchMaxResults()}（默认 15，上限 50），
 * 不受通用 {@code maxResults} 护栏约束——该供应商以「高容量检索」为定位。</p>
 *
 * <p>失败语义：捕获所有异常返回空列表，不抛出（{@link WebSearchService} 契约）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "helloai.web-search.provider", havingValue = "bocha-ai-search")
public class AiSearchWebSearchServiceImpl implements WebSearchService {

    /** 博查 AI Search 单次 count 上限（官方：最多 50 条）。 */
    private static final int MAX_AI_SEARCH_COUNT = 50;

    private final WebSearchProperties properties;
    private final WebSearchCredentialKeyStore credentialKeyStore;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiSearchWebSearchServiceImpl(WebSearchProperties properties,
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
    public String provider() { return "bocha-ai-search"; }

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
                    "freshness", "noLimit",
                    "count", 1,
                    "answer", false,
                    "stream", false
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAiSearchBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                boolean authFail = response.statusCode() == 401 || response.statusCode() == 403;
                log.warn("博查 AI Search Key 验证返回非 2xx: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));
                return verifyResult(false,
                        (authFail ? "API Key 无效或无权限" : "验证失败（HTTP " + response.statusCode() + "）")
                                + "：" + truncate(response.body(), 120), start);
            }
            return verifyResult(true, "验证通过，博查 AI Search API Key 有效（" + elapsed(start) + "ms）", start);
        } catch (Exception e) {
            log.warn("博查 AI Search Key 验证异常（已降级为失败结果）: err={}", e.getMessage());
            return verifyResult(false, "验证请求失败：" + e.getMessage(), start);
        }
    }

    private static Map<String, Object> verifyResult(boolean success, String message, long start) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success);
        out.put("supported", true);
        out.put("message", message);
        out.put("elapsedMs", elapsed(start));
        return out;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    @Override
    public List<WebSearchResult> search(String query, int maxResults) {
        String apiKey = credentialKeyStore.resolveBochaApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("博查 API Key 未配置（系统设置页「联网搜索」或 env BOCHA_API_KEY），跳过本次搜索");
            return List.of();
        }
        if (query == null || query.isBlank()) return List.of();

        // provider 级容量：AI Search 是高容量检索，走专用条数（默认 15，上限 50），
        // 不受通用 maxResults 护栏约束（见类注释）
        int limit = Math.max(1, Math.min(properties.getAiSearchMaxResults(), MAX_AI_SEARCH_COUNT));
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "freshness", "noLimit",
                    "count", limit,
                    "answer", false,
                    "stream", false
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAiSearchBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("博查 AI Search 返回非 2xx: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));
                return List.of();
            }
            return parseResponse(response.body(), limit);
        } catch (Exception e) {
            log.warn("博查 AI Search 失败（已降级为空列表）: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * AI Search 响应解析（双路径兼容）：
     * <ol>
     *   <li>{@code data.webPages.value} —— Web Search 同构路径（防 API 形态演进兜底）；</li>
     *   <li>{@code messages[]} 中 {@code type=source / content_type=webpage} 项，
     *       {@code content}（对象或 JSON 字符串）内的 {@code value[]} —— AI Search 实际结构。</li>
     * </ol>
     * 每条 value 含 name / url / summary（AI 摘要）→ snippet / siteName 等字段。
     */
    private List<WebSearchResult> parseResponse(String body, int limit) {
        List<WebSearchResult> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            int maxSnippet = properties.getMaxSnippetChars();
            // 路径一：data.webPages.value（Web Search 同构）
            JsonNode webPages = root.path("data").path("webPages").path("value");
            if (webPages.isArray() && webPages.size() > 0) {
                for (JsonNode v : webPages) {
                    if (out.size() >= limit) break;
                    addIfValid(out, v, maxSnippet);
                }
                return out;
            }
            // 路径二：messages[] → source/webpage → content → value[]
            JsonNode messages = root.path("messages");
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    if (out.size() >= limit) break;
                    if (!"source".equals(textOrNull(msg.path("type")))) continue;
                    if (!"webpage".equals(textOrNull(msg.path("content_type")))) continue;
                    JsonNode content = msg.path("content");
                    if (content.isTextual()) {
                        try {
                            content = objectMapper.readTree(content.asText());
                        } catch (Exception e) {
                            log.warn("博查 AI Search source content 非 JSON 字符串，跳过: err={}", e.getMessage());
                            continue;
                        }
                    }
                    JsonNode values = content.path("value");
                    if (!values.isArray()) continue;
                    for (JsonNode v : values) {
                        if (out.size() >= limit) break;
                        addIfValid(out, v, maxSnippet);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("博查 AI Search 响应解析失败: err={}", e.getMessage());
            return out;
        }
        return out;
    }

    /** 提取单条 value 为 WebSearchResult（name/url/summary→snippet/siteName），缺正文则跳过。 */
    private void addIfValid(List<WebSearchResult> out, JsonNode v, int maxSnippet) {
        String title = textOrNull(v.path("name"));
        String url = textOrNull(v.path("url"));
        // 优先 AI 摘要（summary），缺则回退 snippet
        String raw = textOrNull(v.path("summary"));
        if (raw == null) raw = textOrNull(v.path("snippet"));
        if (raw == null) return;
        out.add(WebSearchResult.builder()
                .title(safe(title, "(无标题)"))
                .url(safe(url, ""))
                .snippet(truncate(raw, maxSnippet))
                .siteName(textOrNull(v.path("siteName")))
                .build());
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
