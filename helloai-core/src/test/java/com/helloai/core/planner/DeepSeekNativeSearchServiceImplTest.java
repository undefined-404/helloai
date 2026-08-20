package com.helloai.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.impl.DeepSeekNativeSearchServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeepSeekNativeSearchServiceImpl} 单元测试（DeepSeek 原生搜索 adapter）。
 *
 * <p>用 JDK 内置 {@link HttpServer} 起本地桩端点模拟 DeepSeek Anthropic 兼容端点，
 * 验证：请求报文形状（服务端工具声明 / x-api-key 头）、结构化结果块解析映射、
 * 失败降级（非 2xx / 缺 Key / 空查询 / 无结果块）一律返回空列表不抛异常。</p>
 */
@DisplayName("DeepSeek 原生联网搜索 adapter")
class DeepSeekNativeSearchServiceImplTest {

    private static final String API_KEY = "sk-test-deepseek";

    private HttpServer server;
    private WebSearchProperties properties;
    private DeepSeekNativeSearchServiceImpl service;
    private final AtomicInteger hitCount = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();

    /** 桩响应：默认回 Anthropic 格式搜索结果（由用例覆盖）。 */
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void setUp() throws Exception {
        hitCount.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages", exchange -> {
            hitCount.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        properties = new WebSearchProperties();
        properties.setDeepseekApiKey(API_KEY);
        properties.setDeepseekBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/messages");
        properties.setDeepseekTimeoutMs(5_000L);
        properties.setMaxSnippetChars(30);
        properties.setMaxResults(5);
        service = new DeepSeekNativeSearchServiceImpl(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static String searchResponse(int resultCount, String contentField) {
        StringBuilder results = new StringBuilder();
        for (int i = 1; i <= resultCount; i++) {
            if (i > 1) results.append(',');
            results.append("{\"type\":\"web_search_result\",\"title\":\"标题").append(i)
                    .append("\",\"url\":\"https://site").append(i).append(".example/page").append(i)
                    .append("\",\"page_age\":\"2026-08-01\",\"").append(contentField)
                    .append("\":\"这是第").append(i).append("条引用原文，长度刻意超出截断阈值用于验证截断逻辑\"}");
        }
        return "{\"id\":\"msg-1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"server_tool_use\",\"id\":\"stu-1\",\"name\":\"web_search\",\"input\":{\"query\":\"q\"}},"
                + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"stu-1\",\"content\":[" + results + "]},"
                + "{\"type\":\"text\",\"text\":\"最终正文（adapter 不消费）\"}"
                + "],\"stop_reason\":\"end_turn\"}";
    }

    @Test
    @DisplayName("provider 标识为 deepseek-native")
    void provider_returnsDeepSeekNative() {
        assertThat(service.provider()).isEqualTo("deepseek-native");
    }

    @Test
    @DisplayName("正常响应：解析 web_search_tool_result 块映射为 WebSearchResult 列表")
    void search_parsesWebSearchToolResultBlock() {
        responseBody = searchResponse(2, "content");

        List<WebSearchResult> results = service.search("60 天备考架构师", 5);

        assertThat(results).hasSize(2);
        WebSearchResult first = results.get(0);
        assertThat(first.getTitle()).isEqualTo("标题1");
        assertThat(first.getUrl()).isEqualTo("https://site1.example/page1");
        // siteName 由 URL host 推导
        assertThat(first.getSiteName()).isEqualTo("site1.example");
        // 引用原文未超 maxSnippetChars=30 → 全文保留不截断
        assertThat(first.getSnippet()).contains("第1条引用原文").doesNotEndWith("…");
        assertThat(first.getSnippet().length()).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("snippet 超过 maxSnippetChars 时截断并追加省略号")
    void search_truncatesLongSnippet() {
        // 构造 60 字超长引用原文，阈值 30 → 截断为 30 字 + 省略号
        String longText = "甲".repeat(60);
        responseBody = "{\"content\":[{\"type\":\"web_search_tool_result\",\"content\":["
                + "{\"type\":\"web_search_result\",\"title\":\"长文本\","
                + "\"url\":\"https://long.example/a\",\"content\":\"" + longText + "\"}]}]}";

        List<WebSearchResult> results = service.search("q", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSnippet()).hasSize(31).endsWith("…");
    }

    @Test
    @DisplayName("请求报文：声明 web_search_20250305 服务端工具并携带查询词与 x-api-key")
    void search_sendsServerToolRequestWithApiKey() {
        responseBody = searchResponse(1, "content");

        service.search("报表方案参考", 5);

        assertThat(hitCount.get()).isEqualTo(1);
        assertThat(lastApiKeyHeader.get()).isEqualTo(API_KEY);
        assertThat(lastBody.get())
                .contains("web_search_20250305")
                .contains("Perform a web search for query: 报表方案参考")
                .contains("deepseek-chat");
    }

    @Test
    @DisplayName("limit 生效：结果条数超过 maxResults 时截断")
    void search_truncatesToLimit() {
        responseBody = searchResponse(3, "content");

        List<WebSearchResult> results = service.search("q", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(1).getTitle()).isEqualTo("标题2");
    }

    @Test
    @DisplayName("引用原文字段回退：content 缺失时取 snippet")
    void search_fallsBackToSnippetField() {
        responseBody = searchResponse(1, "snippet");

        List<WebSearchResult> results = service.search("q", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSnippet()).contains("第1条引用原文");
    }

    @Test
    @DisplayName("非 2xx 响应：降级空列表不抛异常")
    void search_non2xx_returnsEmpty() {
        responseStatus = 401;
        responseBody = "{\"error\":{\"message\":\"invalid api key\"}}";

        assertThat(service.search("q", 5)).isEmpty();
    }

    @Test
    @DisplayName("响应无搜索结果块（仅正文）：返回空列表")
    void search_noResultBlock_returnsEmpty() {
        responseBody = "{\"content\":[{\"type\":\"text\",\"text\":\"无搜索发生\"}]}";

        assertThat(service.search("q", 5)).isEmpty();
    }

    @Test
    @DisplayName("API Key 未配置：不发请求直接空列表")
    void search_blankApiKey_skipsHttpCall() {
        properties.setDeepseekApiKey("");

        assertThat(service.search("q", 5)).isEmpty();
        assertThat(hitCount.get()).isZero();
    }

    @Test
    @DisplayName("查询词为空白：不发请求直接空列表")
    void search_blankQuery_skipsHttpCall() {
        assertThat(service.search("   ", 5)).isEmpty();
        assertThat(hitCount.get()).isZero();
    }

    @Test
    @DisplayName("响应 JSON 非法：解析失败降级空列表不抛异常")
    void search_malformedJson_returnsEmpty() {
        responseBody = "not-a-json{{";

        assertThat(service.search("q", 5)).isEmpty();
    }
}
