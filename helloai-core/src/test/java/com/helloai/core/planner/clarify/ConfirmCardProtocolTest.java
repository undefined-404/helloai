package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfirmCardProtocol 单测——确认卡协议（纯确认意图形态）。
 *
 * <p><b>2026-10-02 语义反转（选项 A）</b>：原 {@code buildAskPayload(String)} /
 * {@code buildAskText(String)} 重载把联合决策 LLM 产出的「澄清问题」直通卡片题面与可读正文，
 * 但该卡只有「确认/取消」两个选项且 {@code allowCustom=false} —— 题面是要自由回答的澄清问题，
 * 交互却只能点「确认/取消」且无输入框，用户无法回答；点「确认」只切模式，问题被静默丢弃。
 * 故确认卡恢复<b>纯确认意图</b>：题面与正文恒为默认文案，澄清问题由 CLARIFY 模式下一轮正式生成。
 * 本测试反转原断言，锁定「确认卡不承载澄清问题」。</p>
 */
@DisplayName("ConfirmCardProtocol")
class ConfirmCardProtocolTest {

    private ConfirmCardProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new ConfirmCardProtocol(new ObjectMapper());
    }

    @Test
    @DisplayName("确认卡 payload：题面恒为默认确认文案、选项仅确认/取消、禁自定义输入（不承载澄清问题）")
    void shouldAlwaysUseDefaultQuestionText() throws Exception {
        String payload = protocol.buildAskPayload();

        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode cardQuestion = root.get("questions").get(0);
        assertThat(cardQuestion.get("id").asText()).isEqualTo(ConfirmCardProtocol.CONFIRM_QUESTION_ID);
        assertThat(cardQuestion.get("text").asText()).isEqualTo(ConfirmCardProtocol.CONFIRM_QUESTION_TEXT);
        assertThat(cardQuestion.get("multiple").asBoolean()).isFalse();
        assertThat(cardQuestion.get("allowCustom").asBoolean()).isFalse();
        JsonNode options = cardQuestion.get("options");
        assertThat(options).hasSize(2);
        assertThat(options.get(0).get("label").asText()).isEqualTo(ConfirmCardProtocol.CONFIRM_OPTION_ACCEPT);
        assertThat(options.get(1).get("label").asText()).isEqualTo(ConfirmCardProtocol.CONFIRM_OPTION_CANCEL);
    }

    @Test
    @DisplayName("确认卡可读正文：恒为默认正文（不含澄清问题）")
    void shouldAlwaysUseDefaultAskText() {
        assertThat(protocol.buildAskText()).isEqualTo(ConfirmCardProtocol.CONFIRM_ASK_TEXT);
    }

    @Test
    @DisplayName("带澄清问题的重载（遗留）：仍回退默认题面，保证旧调用方不会把问题塞进确认卡")
    void shouldFallbackToDefaultEvenWhenQuestionProvided() throws Exception {
        String question = "你希望这套方案覆盖哪些核心场景？";

        String payload = protocol.buildAskPayload(question);

        JsonNode root = new ObjectMapper().readTree(payload);
        assertThat(root.get("questions").get(0).get("text").asText())
                .isEqualTo(ConfirmCardProtocol.CONFIRM_QUESTION_TEXT);
        assertThat(protocol.buildAskText(question)).isEqualTo(ConfirmCardProtocol.CONFIRM_ASK_TEXT);
    }
}
