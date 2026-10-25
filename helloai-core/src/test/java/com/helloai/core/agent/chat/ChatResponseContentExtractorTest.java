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

    // ================================
    // extractToolCalls：跨 Generation 汇总
    // （2026-10-25 MiniMax「从不调用工具」事故回归）
    // ================================

    @Test
    @DisplayName("★核心回归：Anthropic 协议把正文与 tool_use 拆两个 Generation ⇒ 工具调用不得被第 0 个遮蔽")
    void toolCallsAggregatedAcrossGenerationsForAnthropicShape() {
        // 复刻 AnthropicChatModel.toChatResponse 的真实形态：
        // gen 0 = 正文（无工具），gen 1 = 工具调用（追加在末尾，正文为空）
        AssistantMessage textOnly = AssistantMessage.builder()
                .content("我先查一下子任务详情。").build();
        AssistantMessage toolOnly = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "toolu_01", "function", "internal_get_sub_task_detail", "{\"subTaskId\":\"1\"}")))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(textOnly), new Generation(toolOnly)), null);

        // 事故现场：只取第 0 个 ⇒ 永远拿不到工具调用
        assertThat(response.getResult().getOutput().getToolCalls()).isEmpty();

        // 修复口径：跨 Generation 汇总 ⇒ 拿得到
        List<AssistantMessage.ToolCall> toolCalls =
                ChatResponseContentExtractor.extractToolCalls(response);
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).name()).isEqualTo("internal_get_sub_task_detail");
        assertThat(toolCalls.get(0).id()).isEqualTo("toolu_01");
    }

    @Test
    @DisplayName("OpenAI 形态（单 Generation 自带工具调用）行为不变")
    void singleGenerationToolCallsUnchanged() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "web_search", "{\"q\":\"x\"}")))
                .build();

        List<AssistantMessage.ToolCall> toolCalls =
                ChatResponseContentExtractor.extractToolCalls(response(message));

        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0).name()).isEqualTo("web_search");
    }

    @Test
    @DisplayName("多 Generation 重复 id ⇒ 去重（保持首次出现顺序）")
    void duplicateToolCallIdDeduplicated() {
        AssistantMessage.ToolCall first = new AssistantMessage.ToolCall(
                "id_dup", "function", "a_tool", "{}");
        AssistantMessage.ToolCall second = new AssistantMessage.ToolCall(
                "id_b", "function", "b_tool", "{}");
        ChatResponse response = new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(first)).build()),
                new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(first, second)).build())), null);

        List<AssistantMessage.ToolCall> toolCalls =
                ChatResponseContentExtractor.extractToolCalls(response);

        assertThat(toolCalls).extracting(AssistantMessage.ToolCall::id)
                .containsExactly("id_dup", "id_b");
    }

    @Test
    @DisplayName("缺 id 的退化情形 ⇒ 按「名字 + 参数」去重")
    void missingIdFallsBackToNameAndArguments() {
        AssistantMessage.ToolCall noId = new AssistantMessage.ToolCall(
                null, "function", "a_tool", "{\"k\":1}");
        ChatResponse response = new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(noId)).build()),
                new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(noId)).build())), null);

        assertThat(ChatResponseContentExtractor.extractToolCalls(response)).hasSize(1);
    }

    @Test
    @DisplayName("null / 空响应 ⇒ 空列表（恒非 null）")
    void extractToolCallsNullSafe() {
        assertThat(ChatResponseContentExtractor.extractToolCalls(null)).isEmpty();
        assertThat(ChatResponseContentExtractor.extractToolCalls(new ChatResponse(List.of(), null)))
                .isEmpty();
        assertThat(ChatResponseContentExtractor.extractToolCalls(
                response(AssistantMessage.builder().content("纯文本").build()))).isEmpty();
    }
}
