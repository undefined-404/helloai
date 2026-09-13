package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
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
        when(properties.getTimeoutMs()).thenReturn(8000L); // HttpClient.connectTimeout 需 >0
        return new AiSearchWebSearchServiceImpl(properties, credentialKeyStore, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private List<WebSearchResult> parse(String body, int limit) throws Exception {
        Method m = AiSearchWebSearchServiceImpl.class
                .getDeclaredMethod("parseResponse", String.class, int.class);
        m.setAccessible(true);
        return (List<WebSearchResult>) m.invoke(newService(), body, limit);
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
                .getDeclaredMethod("parseResponse", String.class, int.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<WebSearchResult> results = (List<WebSearchResult>) m.invoke(service, body, 15);

        assertThat(results).hasSize(1);
        assertThat(service.answerSummary("q")).contains("博查侧生成的需求总结");
    }
}
