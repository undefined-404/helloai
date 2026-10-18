package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.clarify.RelativeTimeNormalizer;
import com.helloai.core.planner.search.WebModalCard;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** W-3：freshness 动态判定（查询词时效词表单一事实源，与搜索词日期归一化同源）。 */
    private final RelativeTimeNormalizer relativeTimeNormalizer;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 各查询词的大模型总结答案槽（answer=true 时博查侧生成）。
     *
     * <p>W-2 并行搜索引入：多候选词经 {@code CompletableFuture} 并行调 {@link #search} 时，
     * 单槽 {@code lastAnswer} 会被并发覆盖（A 词 search 写、B 词 search 又写 → A 词
     * {@link #answerSummary} 读到 B 词答案）。改 per-query 槽：{@code search(query)} 写
     * 自己键，{@code answerSummary(query)} 只读自己键——并行下各词互不干扰。
     * 语义约束不变：{@code search} 先行、{@code answerSummary} 紧随（编排器层配对调用）。</p>
     */
    private final Map<String, String> answerByQuery = new ConcurrentHashMap<>();

    /**
     * 各查询词的结构化模态卡槽（W-7）：同一次 HTTP 响应内的 {@code content_type != webpage}
     * source 解析所得（天气/百科/股票/汇率/金价/油价 等垂域卡）。
     *
     * <p>与 {@link #answerByQuery} 同款 per-query 语义：并行搜索下各词写自己键、读自己键。
     * 值为不可变列表（解析后 {@code List.copyOf}），空列表表示「该词无卡」——
     * 与「未搜过该词」（键不存在）区分，二者都返回空列表给调用方。</p>
     */
    private final Map<String, List<WebModalCard>> modalCardsByQuery = new ConcurrentHashMap<>();

    /**
     * 纳入记录的模态卡类型白名单前缀（W-7 首批消费范围）。
     *
     * <p>按前缀匹配覆盖博查全部垂域卡族（{@code weather_china} / {@code weather_international}
     * 均命中 {@code weather}）。<b>刻意不含 {@code image} / {@code video}</b>：它们是媒体
     * source 而非「结构化数据卡」，且仅有 URL/尺寸字段，注入 Prompt 无事实价值；
     * 也刻意用白名单而非黑名单——未知新卡类型默认不注入，避免上游新增卡形态撑爆 Prompt。</p>
     */
    private static final List<String> MODAL_CARD_TYPE_PREFIXES = List.of(
            "weather", "baike", "medical", "calendar", "train",
            "star", "gold_price", "exchangerate", "oil_price", "stock", "phone", "car");

    public AiSearchWebSearchServiceImpl(WebSearchProperties properties,
                                        WebSearchCredentialKeyStore credentialKeyStore,
                                        RelativeTimeNormalizer relativeTimeNormalizer,
                                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.credentialKeyStore = credentialKeyStore;
        this.relativeTimeNormalizer = relativeTimeNormalizer;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1L, properties.getAiSearchTimeoutMs())))
                .build();
    }

    @Override
    public String provider() { return "bocha-ai-search"; }

    /** 最近一次搜索该查询词的大模型总结（answer=true 时博查侧生成；answer=false 或无则为 null）。
     *  按 query 精确取槽，支持并行搜索下多词互不干扰（W-2）。 */
    @Override
    public String answerSummary(String query) {
        if (query == null) {
            return null;
        }
        return answerByQuery.get(query);
    }

    /**
     * 该查询词本次搜索附带的结构化模态卡（W-7；无卡/未搜过返回空列表）。
     *
     * <p>与 {@link #answerSummary} 同款配对语义：{@code search} 先行、本方法紧随。</p>
     */
    @Override
    public List<WebModalCard> modalCards(String query) {
        if (query == null) {
            return List.of();
        }
        List<WebModalCard> cards = modalCardsByQuery.get(query);
        return cards == null ? List.of() : cards;
    }

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
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getAiSearchTimeoutMs())))
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
        answerByQuery.remove(query); // 每次搜索重置该词槽，供 answerSummary 读取本次总结（避免上次残留）
        modalCardsByQuery.remove(query); // 同理重置模态卡槽（W-7）
        try {
            // W-3：freshness 由查询词时效动态判定（含时效词收紧档位，否则 noLimit），
            // 替代硬编码 noLimit——对齐成熟产品「时间衰减」检索体验
            String body = buildRequestBody(query, limit);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getAiSearchBaseUrl()))
                    .timeout(Duration.ofMillis(Math.max(1L, properties.getAiSearchTimeoutMs())))
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
            return parseResponse(response.body(), limit, query);
        } catch (Exception e) {
            log.warn("博查 AI Search 失败（已降级为空列表）: query={}, err={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 组装 AI Search 请求体（包私有，供单测直接断言 freshness 动态判定结果，W-3）。
     *
     * @param query 查询词
     * @param limit provider 级结果条数（已收敛 1~50）
     * @return JSON 请求体字符串
     */
    String buildRequestBody(String query, int limit) {
        String freshness = relativeTimeNormalizer.freshnessFor(query, LocalDate.now());
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "freshness", freshness,
                    "count", limit,
                    "answer", properties.isAiSearchAnswer(),
                    "stream", false
            ));
        } catch (Exception e) {
            // 与 search() 的降级语义一致：序列化失败返回最小请求体（query + noLimit）
            log.warn("博查 AI Search 请求体序列化失败，降级最小请求体: err={}", e.getMessage());
            return "{\"query\":\"" + query + "\",\"freshness\":\"noLimit\"}";
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
     *
     * <p>W-7：同一遍扫描顺带收集 {@code type=source} 且 {@code content_type} 命中
     * {@link #MODAL_CARD_TYPE_PREFIXES} 的垂域模态卡（天气/百科/股票/汇率等），
     * 落 {@link #modalCardsByQuery} per-query 槽——不额外发 HTTP 请求。</p>
     *
     * @param body  响应体
     * @param limit 结果条数上限
     * @param query 本次查询词（answer / modalCards 落 per-query 槽用，W-2/W-7）
     */
    private List<WebSearchResult> parseResponse(String body, int limit, String query) {
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
            // 路径二：messages[] → source/webpage → content → value[]（answer/模态卡另取）
            List<WebModalCard> cards = new ArrayList<>();
            JsonNode messages = root.path("messages");
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    String type = textOrNull(msg.path("type"));
                    // 大模型总结消息（answer=true 时博查侧生成）：与结果条数无关，必取
                    if ("answer".equals(type)) {
                        String ans = textOrNull(msg.path("content"));
                        if (ans != null) {
                            answerByQuery.put(query, ans);
                        }
                        continue;
                    }
                    if (!"source".equals(type)) continue;
                    String contentType = textOrNull(msg.path("content_type"));
                    // W-7：垂域模态卡（非 webpage 的 source，命中白名单前缀）
                    if (contentType != null && !"webpage".equals(contentType)) {
                        WebModalCard card = toModalCard(contentType, msg.path("content"), maxSnippet);
                        if (card != null) {
                            cards.add(card);
                        }
                        continue;
                    }
                    if (out.size() >= limit) continue; // 结果已满仍继续扫描（后方可能仍有 answer/模态卡消息）
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
            if (!cards.isEmpty()) {
                modalCardsByQuery.put(query, List.copyOf(cards));
            }
        } catch (Exception e) {
            log.warn("博查 AI Search 响应解析失败: err={}", e.getMessage());
            return out;
        }
        return out;
    }

    /**
     * 把一条模态卡 message 转成 {@link WebModalCard}（W-7）：白名单过滤 + 标题/摘要启发式提取。
     *
     * <p>卡内容是**各卡互不相同**的 JSON（天气有 forecast 数组、股票有报价字段、百科有
     * abstract……），不做逐卡建模（几十种卡 × 频繁演进，维护成本远大于收益）。策略：
     * ① 类型不在白名单 ⇒ 丢弃；② 标题依次尝试 {@code title/name/subject/query}；
     * ③ 摘要优先取文本型正文字段（{@code summary/abstract/description/content/text}），
     * 否则整个 JSON 紧凑序列化后截断——保证「卡的精确数据」总能以某种形式进入 Prompt
     * （对齐 W-7 目标：垂直查询注入含结构化数据）。</p>
     *
     * @return 卡对象；类型不在白名单或内容为空时返回 null
     */
    private WebModalCard toModalCard(String contentType, JsonNode contentNode, int maxSnippet) {
        if (!isModalCardType(contentType)) {
            return null;
        }
        JsonNode content = contentNode;
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            String raw = content.asText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                content = objectMapper.readTree(raw);
            } catch (Exception e) {
                // 非 JSON 文本：直接当摘要用（防御性，官方卡内容均为 JSON 字符串）
                return WebModalCard.builder()
                        .type(contentType)
                        .title(readableTypeName(contentType))
                        .summary(truncate(raw, maxSnippet))
                        .build();
            }
        }
        String title = firstText(content, "title", "name", "subject", "query");
        String summary = firstText(content, "summary", "abstract", "description", "content", "text");
        if (summary == null) {
            // 无文本正文字段（典型：纯结构化数值卡）→ 整个 JSON 紧凑序列化后截断
            try {
                summary = objectMapper.writeValueAsString(content);
            } catch (Exception e) {
                summary = null;
            }
        }
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return WebModalCard.builder()
                .type(contentType)
                .title(title == null ? readableTypeName(contentType) : title)
                .summary(truncate(summary, maxSnippet))
                .build();
    }

    /** 卡类型是否在首批消费白名单（前缀匹配）。 */
    private static boolean isModalCardType(String contentType) {
        for (String prefix : MODAL_CARD_TYPE_PREFIXES) {
            if (contentType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 类型标识转可读标题（{@code weather_china → weather china}，仅回退展示用）。 */
    private static String readableTypeName(String contentType) {
        return contentType.replace('_', ' ');
    }

    /** 按字段名顺序取第一个非空文本字段值（模态卡字段名跨卡不统一，多候选兜底）。 */
    private static String firstText(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String field : fieldNames) {
            JsonNode v = node.path(field);
            if (v.isTextual()) {
                String s = v.asText();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        }
        return null;
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
