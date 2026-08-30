package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfirmCardProtocol 单测——确认卡协议重载（澄清问题文本覆盖默认题面）。
 *
 * <p>前置联合决策（intent=clarify）的澄清问题经 {@code buildAskPayload(String)} /
 * {@code buildAskText(String)} 直通卡片题面与可读正文；null/blank 回退默认文案。
 * 本测试只覆盖新增重载与回退边界，无参方法既有行为由 RequirementClarifyServiceTest 覆盖。</p>
 */
@DisplayName("ConfirmCardProtocol")
class ConfirmCardProtocolTest {

    private ConfirmCardProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new ConfirmCardProtocol(new ObjectMapper());
    }

    @Test
    @DisplayName("传澄清问题：payload.question.text 与可读正文均展示该问题")
    void shouldUseQuestionTextWhenProvided() throws Exception {
        String question = "你希望这套方案覆盖哪些核心场景？";

        String payload = protocol.buildAskPayload(question);

        JsonNode root = new ObjectMapper().readTree(payload);
        JsonNode cardQuestion = root.get("questions").get(0);
        assertThat(cardQuestion.get("id").asText()).isEqualTo(ConfirmCardProtocol.CONFIRM_QUESTION_ID);
        assertThat(cardQuestion.get("text").asText()).isEqualTo(question);
        assertThat(protocol.buildAskText(question))
                .startsWith(ConfirmCardProtocol.CONFIRM_ASK_TEXT)
                .endsWith(question);
    }

    @Test
    @DisplayName("null 澄清问题：回退默认题面 CONFIRM_QUESTION_TEXT 与默认正文")
    void shouldFallbackDefaultWhenNull() {
        String payload = protocol.buildAskPayload(null);

        assertThat(payload).contains("\"" + ConfirmCardProtocol.CONFIRM_QUESTION_TEXT + "\"");
        assertThat(protocol.buildAskText(null)).isEqualTo(ConfirmCardProtocol.CONFIRM_ASK_TEXT);
    }

    @Test
    @DisplayName("空白澄清问题：同样回退默认（blank 视为未提供）")
    void shouldFallbackDefaultWhenBlank() {
        String payload = protocol.buildAskPayload("   ");

        assertThat(payload).contains("\"" + ConfirmCardProtocol.CONFIRM_QUESTION_TEXT + "\"");
        assertThat(protocol.buildAskText(" \n ")).isEqualTo(ConfirmCardProtocol.CONFIRM_ASK_TEXT);
    }

    @Test
    @DisplayName("无参方法委托重载：payload 题面保持默认文案（行为不变）")
    void shouldDelegateNoArgToOverload() {
        assertThat(protocol.buildAskPayload()).isEqualTo(protocol.buildAskPayload(null));
        assertThat(protocol.buildAskText()).isEqualTo(ConfirmCardProtocol.CONFIRM_ASK_TEXT);
    }
}