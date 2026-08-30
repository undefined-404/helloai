package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser.ChatRoundDecision;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser.DecisionParseException;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser.SearchDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatRoundDecisionParser 单测——联合决策 JSON 白名单词表校验（意图路由 + 搜索决策）。
 *
 * <p>覆盖三态：合法全字段解析 / 严格拒绝（未知字段、词表违规、clarify 缺问题）/
 * 宽容降级（web_search 缺失或非对象、need_search 缺失或非布尔、搜索词缺失不丢搜索）/
 * 降级工厂 defaults()。测试即模板（requirement-decision.md 第四节）的解析器绑定，防格式漂移。</p>
 */
@DisplayName("ChatRoundDecisionParser")
class ChatRoundDecisionParserTest {

    private ChatRoundDecisionParser parser;

    @BeforeEach
    void setUp() {
        parser = new ChatRoundDecisionParser(new ObjectMapper());
    }

    @Test
    @DisplayName("chat 全字段：intent/intent_reason/question=null/web_search 完整解析")
    void shouldParseChatDecisionWithFullFields() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":true,"search_query":"AI 行业 最新动态 新闻","reason":"用户询问时效性动态"}}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.intent()).isEqualTo("chat");
        assertThat(decision.intentReason()).isEqualTo("direct_answer");
        assertThat(decision.clarificationQuestion()).isNull();
        assertThat(decision.isClarify()).isFalse();
        assertThat(decision.webSearch().needSearch()).isTrue();
        assertThat(decision.webSearch().searchQuery()).isEqualTo("AI 行业 最新动态 新闻");
        assertThat(decision.webSearch().reason()).isEqualTo("用户询问时效性动态");
    }

    @Test
    @DisplayName("clarify 全字段：携带非空澄清问题，问题文本原样返回")
    void shouldParseClarifyDecisionWithQuestion() {
        String raw = """
                {"intent":"clarify","intent_reason":"task_oriented",
                 "clarification_question":"你希望这套方案覆盖哪些核心场景？",
                 "web_search":{"need_search":false,"search_query":null,"reason":"方案讨论无需搜索"}}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.isClarify()).isTrue();
        assertThat(decision.intentReason()).isEqualTo("task_oriented");
        assertThat(decision.clarificationQuestion()).isEqualTo("你希望这套方案覆盖哪些核心场景？");
        assertThat(decision.webSearch().needSearch()).isFalse();
        assertThat(decision.webSearch().searchQuery()).isNull();
    }

    @Test
    @DisplayName("markdown 代码块围栏包裹：剥离后正常解析")
    void shouldStripFenceBeforeParse() {
        String raw = """
                ```json
                {"intent":"chat","intent_reason":"ambiguous","clarification_question":null,
                 "web_search":{"need_search":false,"search_query":null,"reason":"意图模糊"}}
                ```
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.intent()).isEqualTo("chat");
        assertThat(decision.intentReason()).isEqualTo("ambiguous");
    }

    @Test
    @DisplayName("顶层未知字段：拒绝并抛 DecisionParseException")
    void shouldRejectUnknownTopLevelField() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":false},"extra_field":"漂移字段"}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("extra_field");
    }

    @Test
    @DisplayName("intent 非法词表：拒绝")
    void shouldRejectUnknownIntent() {
        String raw = """
                {"intent":"execute","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":false}}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("intent 非法");
    }

    @Test
    @DisplayName("intent 缺失：拒绝")
    void shouldRejectMissingIntent() {
        String raw = """
                {"intent_reason":"direct_answer","clarification_question":null,"web_search":{"need_search":false}}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("intent 缺失");
    }

    @Test
    @DisplayName("intent_reason 与 intent 不匹配（chat + task_oriented）：拒绝")
    void shouldRejectReasonMismatch() {
        String raw = """
                {"intent":"chat","intent_reason":"task_oriented","clarification_question":null,
                 "web_search":{"need_search":false}}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("intent_reason 与 intent 不匹配");
    }

    @Test
    @DisplayName("clarify 缺少 clarification_question：拒绝")
    void shouldRejectClarifyWithoutQuestion() {
        String raw = """
                {"intent":"clarify","intent_reason":"need_clarification","clarification_question":null,
                 "web_search":{"need_search":false}}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("必须携带非空 clarification_question");
    }

    @Test
    @DisplayName("澄清问题是数字（类型非法）：拒绝")
    void shouldRejectQuestionWithWrongType() {
        String raw = """
                {"intent":"clarify","intent_reason":"need_clarification","clarification_question":123,
                 "web_search":{"need_search":false}}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("clarification_question 必须是字符串或 null");
    }

    @Test
    @DisplayName("chat 携带非空澄清问题（宽容）：忽略问题保留聊天语义")
    void shouldIgnoreQuestionWhenChat() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer",
                 "clarification_question":"你不该问这个问题","web_search":{"need_search":false}}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.intent()).isEqualTo("chat");
        assertThat(decision.clarificationQuestion()).isNull();
    }

    @Test
    @DisplayName("web_search 缺失（合法简写）：按不搜索处理")
    void shouldDefaultNoSearchWhenWebSearchMissing() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.webSearch().needSearch()).isFalse();
        assertThat(decision.webSearch().searchQuery()).isNull();
    }

    @Test
    @DisplayName("web_search 为字符串（非对象）：按不搜索处理")
    void shouldDefaultNoSearchWhenWebSearchNotObject() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,"web_search":"true"}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.webSearch().needSearch()).isFalse();
    }

    @Test
    @DisplayName("web_search 内部未知字段：拒绝（与顶层同白名单策略）")
    void shouldRejectUnknownWebSearchField() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":false,"search_engine":"bing"}}
                """;

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("search_engine");
    }

    @Test
    @DisplayName("need_search=true 但 search_query 缺失：空串兜底，不丢搜索机会")
    void shouldKeepSearchWhenQueryMissing() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":true,"reason":"用户询问时效性行情"}}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.webSearch().needSearch()).isTrue();
        assertThat(decision.webSearch().searchQuery()).isEmpty();
    }

    @Test
    @DisplayName("need_search 非布尔（宽容）：按 false 处理，搜索词原样保留（ALWAYS_ON 优化词不丢）")
    void shouldDefaultFalseWhenNeedSearchNotBoolean() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":"yes","search_query":"AI 新闻","reason":"测试"}}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.webSearch().needSearch()).isFalse();
        assertThat(decision.webSearch().searchQuery()).isEqualTo("AI 新闻");
    }

    @Test
    @DisplayName("need_search=false 但 search_query 非空：优化词保留（ALWAYS_ON 模式只取优化词不看 need）")
    void shouldKeepQueryEvenWhenSearchNotNeeded() {
        String raw = """
                {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                 "web_search":{"need_search":false,"search_query":"最新行情","reason":"每轮搜索"}}
                """;

        ChatRoundDecision decision = parser.parse(raw);

        assertThat(decision.webSearch().needSearch()).isFalse();
        assertThat(decision.webSearch().searchQuery()).isEqualTo("最新行情");
    }

    @Test
    @DisplayName("非 JSON 输出：拒绝并附摘要")
    void shouldRejectNonJsonOutput() {
        String raw = "帮我查一下今天的天气\n顺便回答一下问题";

        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOf(DecisionParseException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("降级工厂 defaults()：intent=chat、不搜索、intentReason=null")
    void shouldBuildDefaults() {
        ChatRoundDecision decision = ChatRoundDecisionParser.defaults();

        assertThat(decision.intent()).isEqualTo("chat");
        assertThat(decision.intentReason()).isNull();
        assertThat(decision.clarificationQuestion()).isNull();
        assertThat(decision.isClarify()).isFalse();
        SearchDecision search = decision.webSearch();
        assertThat(search.needSearch()).isFalse();
        assertThat(search.searchQuery()).isNull();
        assertThat(search.reason()).isNull();
    }
}