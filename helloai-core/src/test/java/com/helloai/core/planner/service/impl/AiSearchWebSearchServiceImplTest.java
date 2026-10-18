package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.clarify.RelativeTimeNormalizer;
import com.helloai.core.planner.search.WebModalCard;
import com.helloai.core.planner.search.WebSearchCredentialKeyStore;
import com.helloai.core.planner.search.WebSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link AiSearchWebSearchServiceImpl} 响应解析单测（双路径兼容）。
 *
 * <p>核心断言点：
 * <ul>
 *   <li>路径二（AI Search 实际结构 {@code messages[] → source/webpage → content → value[]}）
 *       正确提取网页列表，summary 优先、snippet 回退、siteName 映射、snippet 截断；</li>
 *   <li>路径一（Web Search 同构 {@code data.webPages.value}）兼容兜底；</li>
 *   <li>content 为 JSON 字符串形态兼容；无正文条目跳过。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiSearchWebSearchServiceImpl AI Search 响应解析")
class AiSearchWebSearchServiceImplTest {

    @Mock
    private WebSearchProperties properties;

    @Mock
    private WebSearchCredentialKeyStore credentialKeyStore;

    private AiSearchWebSearchServiceImpl newService() {
        when(properties.getAiSearchTimeoutMs()).thenReturn(25_000L); // HttpClient.connectTimeout 需 >0
        // W-7：模态卡开关默认 true（properties 类默认值），mock 需显式给；lenient 避免无用例消费时报错
        lenient().when(properties.isModalCardsEnabled()).thenReturn(true);
        return new AiSearchWebSearchServiceImpl(properties, credentialKeyStore,
                new RelativeTimeNormalizer(), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private List<WebSearchResult> parse(String body, int limit) throws Exception {
        Method m = AiSearchWebSearchServiceImpl.class
                .getDeclaredMethod("parseResponse", String.class, int.class, String.class);
        m.setAccessible(true);
        return (List<WebSearchResult>) m.invoke(newService(), body, limit, "q");
    }

    /** W-7：需要读取同一实例的 per-query 模态卡槽，故返回 service 供后续断言。 */
    private AiSearchWebSearchServiceImpl parseOn(AiSearchWebSearchServiceImpl service,
                                                 String body, int limit, String query) throws Exception {
        Method m = AiSearchWebSearchServiceImpl.class
                .getDeclaredMethod("parseResponse", String.class, int.class, String.class);
        m.setAccessible(true);
        m.invoke(service, body, limit, query);
        return service;
    }

    @Test
    @DisplayName("路径二：messages[] → source/webpage → content.value 提取，summary 优先 + siteName + 截断")
    void parseAiSearchMessagesStructure() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(10);
        String body = """
                {
                  "log_id": "L1",
                  "conversation_id": "C1",
                  "messages": [
                    {"role": "user", "type": "text", "content": "hi"},
                    {
                      "role": "assistant", "type": "source", "content_type": "webpage",
                      "content": {
                        "webSearchUrl": "https://bochaai.com/search?q=x",
                        "value": [
                          {"name": "网页一", "url": "https://a.com/1", "summary": "第一条完整摘要很长很长很长很长", "siteName": "站点A"},
                          {"name": "网页二", "url": "https://b.com/2", "snippet": "只有 snippet 的条目", "siteName": "站点B"}
                        ]
                      }
                    }
                  ]
                }
                """;
        List<WebSearchResult> results = parse(body, 15);

        assertThat(results).hasSize(2);
        // summary 优先 + 截断到 maxSnippetChars（10 字符 + "…" = 11）
        assertThat(results.get(0).getTitle()).isEqualTo("网页一");
        assertThat(results.get(0).getUrl()).isEqualTo("https://a.com/1");
        assertThat(results.get(0).getSnippet()).hasSize(11).startsWith("第一条完整摘要").endsWith("…");
        assertThat(results.get(0).getSiteName()).isEqualTo("站点A");
        // snippet 回退 + 同样截断（14 字符 > 10 → 前 10 + "…"）
        assertThat(results.get(1).getSnippet()).hasSize(11).startsWith("只有").endsWith("…");
        assertThat(results.get(1).getSiteName()).isEqualTo("站点B");
    }

    @Test
    @DisplayName("路径二：content 为 JSON 字符串形态兼容")
    void parseAiSearchContentAsJsonString() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [
                    {
                      "role": "assistant", "type": "source", "content_type": "webpage",
                      "content": "{\\"webSearchUrl\\":\\"https://bochaai.com/search?q=y\\",\\"value\\":[{\\"name\\":\\"字符串内容页\\",\\"url\\":\\"https://c.com/3\\",\\"snippet\\":\\"内容\\"}]}"
                    }
                  ]
                }
                """;
        List<WebSearchResult> results = parse(body, 15);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("字符串内容页");
        assertThat(results.get(0).getUrl()).isEqualTo("https://c.com/3");
    }

    @Test
    @DisplayName("路径一：data.webPages.value 兼容兜底（Web Search 同构）")
    void parseWebSearchCompatiblePath() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "code": 200,
                  "data": {
                    "webPages": {
                      "webSearchUrl": "https://bochaai.com/search?q=z",
                      "value": [
                        {"name": "兼容路径页", "url": "https://d.com/4", "summary": "兼容摘要", "siteName": "站点D"}
                      ]
                    }
                  }
                }
                """;
        List<WebSearchResult> results = parse(body, 15);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("兼容路径页");
        assertThat(results.get(0).getSnippet()).isEqualTo("兼容摘要");
    }

    @Test
    @DisplayName("limit 上限：结果条数不超过传入 limit")
    void parseRespectsLimit() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) values.append(',');
            values.append("{\"name\":\"页").append(i).append("\",\"url\":\"https://x.com/").append(i)
                    .append("\",\"snippet\":\"s").append(i).append("\"}");
        }
        String body = "{\"messages\":[{\"role\":\"assistant\",\"type\":\"source\","
                + "\"content_type\":\"webpage\",\"content\":{\"value\":[" + values + "]}}]}";
        List<WebSearchResult> results = parse(body, 3);

        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("无正文条目跳过：缺 summary/snippet 不产出")
    void skipEntriesWithoutText() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [{
                    "role": "assistant", "type": "source", "content_type": "webpage",
                    "content": {
                      "value": [
                        {"name": "有摘要", "url": "https://e.com/5", "snippet": "正文"},
                        {"name": "无正文", "url": "https://f.com/6"}
                      ]
                    }
                  }]
                }
                """;
        List<WebSearchResult> results = parse(body, 15);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("有摘要");
    }

    @Test
    @DisplayName("answer 消息解析：大模型总结落 answerSummary（与结果条数无关）")
    void parseAnswerMessage() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [
                    {"role": "assistant", "type": "answer", "content": "这是博查侧生成的需求总结：平台应包含任务/里程碑/协作三模块。"},
                    {
                      "role": "assistant", "type": "source", "content_type": "webpage",
                      "content": {"value": [
                        {"name": "参考页", "url": "https://g.com/7", "snippet": "摘要"}
                      ]}
                    }
                  ]
                }
                """;
        AiSearchWebSearchServiceImpl service = newService();
        Method m = AiSearchWebSearchServiceImpl.class
                .getDeclaredMethod("parseResponse", String.class, int.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<WebSearchResult> results = (List<WebSearchResult>) m.invoke(service, body, 15, "q");

        assertThat(results).hasSize(1);
        assertThat(service.answerSummary("q")).contains("博查侧生成的需求总结");
    }

    // ════════════════════════════════════════════════════════════
    //  buildRequestBody（W-3：freshness 动态判定接线）
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildRequestBody：含时效词 → freshness 收紧（今天→oneDay，本周→oneWeek）")
    void buildRequestBody_timeSensitiveQuery_dynamicFreshness() throws Exception {
        when(properties.isAiSearchAnswer()).thenReturn(true);
        AiSearchWebSearchServiceImpl service = newService();
        java.util.regex.Pattern fresh = java.util.regex.Pattern.compile("\"freshness\":\"(\\w+)\"");

        String today = service.buildRequestBody("今天上证指数", 15);
        java.util.regex.Matcher mToday = fresh.matcher(today);
        assertThat(mToday.find()).isTrue();
        assertThat(mToday.group(1)).isEqualTo("oneDay");

        String week = service.buildRequestBody("本周AI新闻", 15);
        java.util.regex.Matcher mWeek = fresh.matcher(week);
        assertThat(mWeek.find()).isTrue();
        assertThat(mWeek.group(1)).isEqualTo("oneWeek");
    }

    @Test
    @DisplayName("buildRequestBody：无时效词 → noLimit；answer 透传 properties.isAiSearchAnswer")
    void buildRequestBody_plainQuery_noLimitAndAnswerPassthrough() throws Exception {
        when(properties.isAiSearchAnswer()).thenReturn(true);
        AiSearchWebSearchServiceImpl service = newService();

        String body = service.buildRequestBody("Python 教程", 15);
        assertThat(body)
                .contains("\"freshness\":\"noLimit\"")
                .contains("\"answer\":true")
                .contains("\"count\":15");
    }

    @Test
    @DisplayName("buildRequestBody：answer 关闭时透传 false")
    void buildRequestBody_answerDisabled_passthroughFalse() throws Exception {
        when(properties.isAiSearchAnswer()).thenReturn(false);
        AiSearchWebSearchServiceImpl service = newService();

        String body = service.buildRequestBody("最新行业动态", 15);
        assertThat(body)
                .contains("\"freshness\":\"oneYear\"") // 宽泛时效词「最新」→ oneYear
                .contains("\"answer\":false");
    }

    // ---------- W-7 模态卡消费 ----------

    @Test
    @DisplayName("W-7：垂域模态卡（weather_china）进入 per-query 槽，title/summary 启发式提取")
    void parseModalCard_weather_extractedIntoQuerySlot() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [
                    {
                      "role": "assistant", "type": "source", "content_type": "weather_china",
                      "content": "{\\"title\\":\\"北京天气\\",\\"summary\\":\\"今天晴，26℃，微风\\"}"
                    }
                  ]
                }
                """;
        AiSearchWebSearchServiceImpl service = parseOn(newService(), body, 15, "北京天气");

        List<WebModalCard> cards = service.modalCards("北京天气");
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getType()).isEqualTo("weather_china");
        assertThat(cards.get(0).getTitle()).isEqualTo("北京天气");
        assertThat(cards.get(0).getSummary()).contains("26℃");
        // 未搜过的词不串槽
        assertThat(service.modalCards("其他词")).isEmpty();
    }

    @Test
    @DisplayName("W-7：白名单外 content_type（image / video）不产生模态卡")
    void parseModalCard_nonWhitelistedType_ignored() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [
                    {"role": "assistant", "type": "source", "content_type": "image",
                     "content": "{\\"contentUrl\\":\\"https://img.example/a.png\\",\\"width\\":100}"},
                    {"role": "assistant", "type": "source", "content_type": "video",
                     "content": "{\\"contentUrl\\":\\"https://v.example/a.mp4\\"}"}
                  ]
                }
                """;
        AiSearchWebSearchServiceImpl service = parseOn(newService(), body, 15, "q");

        assertThat(service.modalCards("q")).isEmpty();
    }

    @Test
    @DisplayName("W-7：纯结构化数值卡无文本正文字段 → JSON 紧凑序列化兜底（保证精确数据进 Prompt）")
    void parseModalCard_structuredOnlyFields_fallsBackToJsonSummary() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [
                    {
                      "role": "assistant", "type": "source", "content_type": "gold_price",
                      "content": "{\\"title\\":\\"今日金价\\",\\"price\\":612.35,\\"unit\\":\\"元/克\\",\\"change\\":1.2}"
                    }
                  ]
                }
                """;
        AiSearchWebSearchServiceImpl service = parseOn(newService(), body, 15, "今日金价");

        List<WebModalCard> cards = service.modalCards("今日金价");
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getTitle()).isEqualTo("今日金价");
        assertThat(cards.get(0).getSummary()).contains("612.35").contains("元/克");
    }

    @Test
    @DisplayName("W-7：模态卡与网页结果共存——网页走 results，卡走 modalCards，互不侵占条数上限")
    void parseModalCard_coexistsWithWebpageResults() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {
                  "messages": [
                    {
                      "role": "assistant", "type": "source", "content_type": "stock",
                      "content": "{\\"title\\":\\"上证指数\\",\\"summary\\":\\"收盘 3210.55，涨 0.82%\\"}"
                    },
                    {
                      "role": "assistant", "type": "source", "content_type": "webpage",
                      "content": {"value": [{"name": "行情页", "url": "https://a.com/1", "snippet": "大盘分析"}]}
                    }
                  ]
                }
                """;
        AiSearchWebSearchServiceImpl service = newService();
        Method m = AiSearchWebSearchServiceImpl.class
                .getDeclaredMethod("parseResponse", String.class, int.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<WebSearchResult> results = (List<WebSearchResult>) m.invoke(service, body, 1, "上证指数");

        assertThat(results).as("limit=1 只收 1 条网页，模态卡不占位").hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("行情页");
        assertThat(service.modalCards("上证指数")).hasSize(1);
    }

    @Test
    @DisplayName("W-7：webPages 路径（Web Search 同构）早退——不产生模态卡，也不误判")
    void parseModalCard_webPagesPath_returnsEarly() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        String body = """
                {"data": {"webPages": {"value": [{"name": "页", "url": "https://a.com/1", "snippet": "x"}]}}}
                """;
        AiSearchWebSearchServiceImpl service = parseOn(newService(), body, 15, "q");

        assertThat(service.modalCards("q")).isEmpty();
    }

    @Test
    @DisplayName("W-7 kill switch：modal-cards-enabled=false → 完全不收集模态卡")
    void parseModalCard_disabledBySwitch_noCards() throws Exception {
        when(properties.getMaxSnippetChars()).thenReturn(200);
        when(properties.getAiSearchTimeoutMs()).thenReturn(25_000L);
        when(properties.isModalCardsEnabled()).thenReturn(false);
        AiSearchWebSearchServiceImpl service = new AiSearchWebSearchServiceImpl(properties,
                credentialKeyStore, new RelativeTimeNormalizer(), new ObjectMapper());
        String body = """
                {
                  "messages": [
                    {"role": "assistant", "type": "source", "content_type": "gold_price",
                     "content": "{\\"title\\":\\"今日金价\\",\\"summary\\":\\"612 元/克\\"}"}
                  ]
                }
                """;
        parseOn(service, body, 15, "今日金价");

        assertThat(service.modalCards("今日金价")).isEmpty();
    }
}
