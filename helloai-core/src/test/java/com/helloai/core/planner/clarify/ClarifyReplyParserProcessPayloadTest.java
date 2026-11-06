package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.planner.search.WebModalCard;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RoundProcess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过程数据 payload 契约（2026-10-18）：
 *
 * <p>用户实测「思考过程在回复完成后消失」的根因是——思考只作为 SSE 一次性帧推送，
 * 未随消息落库。本组用例锁定「process 必须进 payload，且空过程不写该键」，
 * 保证前端 done 后 / 刷新后 / 换设备后都能从消息里重建过程区（对齐 DeepSeek / ChatGPT）。</p>
 */
@DisplayName("过程数据 payload 契约（思考 + 检索步骤落库）")
class ClarifyReplyParserProcessPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ClarifyReplyParser parser = new ClarifyReplyParser(objectMapper);

    @Test
    @DisplayName("纯过程 payload：thinking + steps 均写入 process 键")
    void webSearchOnlyPayload_carriesProcess() throws Exception {
        RoundProcess process = new RoundProcess("先看用户问的是不是时效性问题……",
                List.of("正在联网检索：最近的AI技术新闻么", "已检索 15 条结果，耗时 21.5s"));

        JsonNode node = objectMapper.readTree(parser.buildWebSearchOnlyPayload(null, process));

        assertThat(node.path("process").path("thinking").asText()).contains("时效性问题");
        assertThat(node.path("process").path("steps")).hasSize(2);
        assertThat(node.path("process").path("steps").get(1).asText()).contains("已检索 15 条");
        // 无 webSearch 时不应出现该键（不写空对象）
        assertThat(node.has("webSearch")).isFalse();
    }

    @Test
    @DisplayName("空过程 + 无搜索 ⇒ 不写 payload（返回 null，避免给纯文本回复塞空对象）")
    void webSearchOnlyPayload_emptyProcessAndNoSearch_returnsNull() {
        assertThat(parser.buildWebSearchOnlyPayload(null, RoundProcess.EMPTY)).isNull();
        assertThat(parser.buildWebSearchOnlyPayload(null, null)).isNull();
        assertThat(parser.buildWebSearchOnlyPayload(null)).isNull();
    }

    @Test
    @DisplayName("追问 payload：mode/progress/questions 与 process 共存，结构引号不被破坏")
    void questionPayload_carriesProcessAlongsideQuestions() throws Exception {
        ClarifyReply reply = new ClarifyReply();
        reply.setType("question");
        reply.setMode("structured");
        reply.setProgress(40);
        reply.setQuestions(List.of());

        // questions 为空 ⇒ structured=false，但 progress 与 process 仍应写入
        JsonNode node = objectMapper.readTree(
                parser.buildQuestionPayload(reply, null, new RoundProcess("思考片段", List.of("步骤一"))));

        assertThat(node.path("mode").asText()).isEqualTo("freeform");
        assertThat(node.path("progress").asInt()).isEqualTo(40);
        assertThat(node.path("process").path("thinking").asText()).isEqualTo("思考片段");
        assertThat(node.path("process").path("steps").get(0).asText()).isEqualTo("步骤一");
    }

    @Test
    @DisplayName("向后兼容：不带 process 的重载与原行为一致（payload 无 process 键）")
    void questionPayload_withoutProcess_hasNoProcessKey() throws Exception {
        ClarifyReply reply = new ClarifyReply();
        reply.setType("question");
        reply.setMode("freeform");
        reply.setProgress(50);

        JsonNode node = objectMapper.readTree(parser.buildQuestionPayload(reply, null));

        assertThat(node.has("process")).isFalse();
        assertThat(node.path("progress").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("W-7 模态卡：webSearch payload 必须序列化 modalCards（空卡不写该键）")
    void webSearchMap_carriesModalCards() throws Exception {
        WebSearchOutcome outcome = WebSearchOutcome.builder()
                .provider("router->bocha-ai-search")
                .query("北京天气")
                .queries(List.of("北京天气"))
                .costMs(1757L)
                .total(1)
                .results(List.of(WebSearchResult.builder().title("结果").url("http://x").build()))
                .modalCards(List.of(
                        WebModalCard.builder().type("weather_china").title("北京今日天气")
                                .summary("晴，14℃…").build(),
                        WebModalCard.builder().type("weather_china").title("北京夜间天气")
                                .summary("多云，2℃…").build()))
                .build();

        JsonNode node = objectMapper.readTree(parser.buildQuestionPayload(new ClarifyReply(), outcome));

        assertThat(node.has("webSearch")).isTrue();
        JsonNode cards = node.path("webSearch").path("modalCards");
        assertThat(cards.isArray()).isTrue();
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).path("type").asText()).isEqualTo("weather_china");
        assertThat(cards.get(0).path("title").asText()).isEqualTo("北京今日天气");
        assertThat(cards.get(0).path("summary").asText()).contains("14℃");
    }

    @Test
    @DisplayName("W-7 模态卡：无卡时 webSearch payload 不写 modalCards 键（前端忽略未知键）")
    void webSearchMap_noModalCards_omitsKey() throws Exception {
        WebSearchOutcome outcome = WebSearchOutcome.builder()
                .provider("router->bocha-ai-search")
                .query("北京")
                .queries(List.of("北京"))
                .costMs(1233L)
                .total(15)
                .results(List.of(WebSearchResult.builder().title("r").url("http://x").build()))
                .build();

        JsonNode node = objectMapper.readTree(parser.buildQuestionPayload(new ClarifyReply(), outcome));

        assertThat(node.path("webSearch").has("modalCards")).isFalse();
    }
}
