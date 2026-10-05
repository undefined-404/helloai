package com.helloai.core.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatResponseContentExtractor} 单测。
 *
 * <p>重点覆盖 2026-10-05 方案 B：DeepSeek 推理模型的 {@code reasoningContent}（思维链）与
 * {@code content}（正文）是**独立字段**，提取器须把思维链归入 thinking，且**绝不并入正文**
 * （避免污染交付物与核验；正文为空时仍由 Runtime 空产出护栏判 FAILED）。</p>
 */
@DisplayName("ChatResponseContentExtractor 正文/思考分离（含 DeepSeek reasoningContent）")
class ChatResponseContentExtractorTest {

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)), null);
    }

    @Test
    @DisplayName("DeepSeek：content 为空、reasoningContent 非空 ⇒ text=\"\"、thinking=思维链（复刻现场）")
    void deepSeekReasoningOnlyKeepsTextEmptyAndThinkingPopulated() {
        DeepSeekAssistantMessage deepSeek = new DeepSeekAssistantMessage.Builder()
                .content("")
                .reasoningContent("我先分析需求，再决定交付物……")
                .build();

        ChatResponseContentExtractor.ExtractedContent extracted =
                ChatResponseContentExtractor.extract(response(deepSeek));

        // 关键断言：正文仍为空（不得被 reasoningContent 污染）
        assertThat(extracted.text()).isEmpty();
        // 关键断言：思维链归入 thinking 通道
        assertThat(extracted.thinking()).isEqualTo("我先分析需求，再决定交付物……");
    }

    @Test
    @DisplayName("DeepSeek：content 与 reasoningContent 都有 ⇒ 正文/思考各归其位")
    void deepSeekKeepsTextAndThinkingSeparate() {
        DeepSeekAssistantMessage deepSeek = new DeepSeekAssistantMessage.Builder()
                .content("## 交付物\nreport.html")
                .reasoningContent("思考过程")
                .build();

        ChatResponseContentExtractor.ExtractedContent extracted =
                ChatResponseContentExtractor.extract(response(deepSeek));

        assertThat(extracted.text()).isEqualTo("## 交付物\nreport.html");
        assertThat(extracted.thinking()).isEqualTo("思考过程");
    }

    @Test
    @DisplayName("普通 AssistantMessage（非 DeepSeek）行为不变：content→text，thinking 为空")
    void plainAssistantMessageUnchanged() {
        AssistantMessage plain = AssistantMessage.builder().content("hello").build();

        ChatResponseContentExtractor.ExtractedContent extracted =
                ChatResponseContentExtractor.extract(response(plain));

        assertThat(extracted.text()).isEqualTo("hello");
        assertThat(extracted.thinking()).isEmpty();
    }

    @Test
    @DisplayName("DeepSeek：reasoningContent 为空白 ⇒ 不计入 thinking（避免空消息）")
    void deepSeekBlankReasoningIgnored() {
        DeepSeekAssistantMessage deepSeek = new DeepSeekAssistantMessage.Builder()
                .content("正文")
                .reasoningContent("   ")
                .build();

        ChatResponseContentExtractor.ExtractedContent extracted =
                ChatResponseContentExtractor.extract(response(deepSeek));

        assertThat(extracted.text()).isEqualTo("正文");
        assertThat(extracted.thinking()).isEmpty();
    }
}
