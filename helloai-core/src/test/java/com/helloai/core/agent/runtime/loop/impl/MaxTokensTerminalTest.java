package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REF-8.7 截断终态与 sticky 语义专项测试。
 *
 * <p>覆盖三件事：① 截断（provider 声明 finishReason 命中 token 上限集）<b>不得静默当成功</b>
 * —— 终态为 {@code MAX_TOKENS} 且 {@code success=false}（改造前该情形直接返回 {@code STOP} 成功）；
 * ② <b>sticky</b>：任一步触顶 ⇒ 本 turn 全部出口（含后续正常 STOP、含轮数耗尽）都不得下调；
 * ③ <b>fail-open</b>：未知 / 缺失结束原因一律不判截断（不因本判据新增拒绝面）。</p>
 */
@DisplayName("REF-8.7 max-tokens 终态 + sticky")
class MaxTokensTerminalTest {

    /** 工具执行恒成功（本测试只关心终态分类，不关心工具语义）。 */
    private static final ToolExecutor TOOL = (name, args) -> ToolExecutionResult.success(name, "ok");

    private final AgentLoop loop = new ChatModelToolLoop();

    @Test
    @DisplayName("★截断（finishReason=LENGTH）⇒ 终态 MAX_TOKENS 且 success=false（改造前为 STOP 成功）")
    void truncatedOutputIsNotSilentlySuccessful() {
        StubChatModel model = new StubChatModel().enqueue(text("半截正文", "LENGTH"));

        AgentLoopResult result = loop.run(input(model, 5));

        assertThat(result.finishReason()).isEqualTo("MAX_TOKENS");
        assertThat(result.success()).isFalse();
        // 已产出的部分正文不丢（证据保留）
        assertThat(result.text()).isEqualTo("半截正文");
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("Anthropic 风格 max_tokens（大小写不敏感）同样识别为截断")
    void anthropicStyleFinishReasonIsDetected() {
        AgentLoopResult result = loop.run(input(new StubChatModel().enqueue(text("x", "max_tokens")), 5));

        assertThat(result.finishReason()).isEqualTo("MAX_TOKENS");
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("★sticky：任一步截断 ⇒ 后续正常 STOP 不得下调本 turn 终态")
    void maxTokensIsStickyAcrossSteps() {
        StubChatModel model = new StubChatModel()
                .enqueue(toolCall("c1", "t", "{}", "LENGTH"))   // 第 1 步：截断（且发了工具调用）
                .enqueue(text("final", "STOP"));                // 第 2 步：正常收尾

        AgentLoopResult result = loop.run(input(model, 5));

        assertThat(result.finishReason()).isEqualTo("MAX_TOKENS");
        assertThat(result.success()).isFalse();
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.text()).isEqualTo("final");
    }

    @Test
    @DisplayName("★sticky：截断优先于轮数耗尽（得 MAX_TOKENS 而非 MAX_ITERATIONS）")
    void truncationBeatsMaxIterations() {
        StubChatModel model = new StubChatModel().enqueue(toolCall("c1", "t", "{}", "LENGTH"));

        AgentLoopResult result = loop.run(input(model, 1));   // 仅 1 轮 ⇒ 走到轮数耗尽出口

        assertThat(result.finishReason()).isEqualTo("MAX_TOKENS");
        assertThat(result.toolCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("未知结束原因（fail-open）⇒ 仍按 STOP 成功，不因本判据新增拒绝面")
    void unknownFinishReasonIsNotTruncation() {
        AgentLoopResult result = loop.run(input(new StubChatModel().enqueue(text("done", "stop_sequence")), 5));

        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("provider 未给结束原因 ⇒ 降级回改造前语义（STOP 成功）")
    void absentFinishReasonDegradesToStop() {
        AgentLoopResult result = loop.run(input(new StubChatModel().enqueue(text("done", null)), 5));

        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("isTruncated：命中集 / 大小写与空白容错 / 未知值")
    void isTruncatedTable() {
        assertThat(ChatModelToolLoop.isTruncated("LENGTH")).isTrue();
        assertThat(ChatModelToolLoop.isTruncated("max_tokens")).isTrue();
        assertThat(ChatModelToolLoop.isTruncated("MAX_TOKENS")).isTrue();
        assertThat(ChatModelToolLoop.isTruncated("  length  ")).isTrue();
        assertThat(ChatModelToolLoop.isTruncated("STOP")).isFalse();
        assertThat(ChatModelToolLoop.isTruncated("tool_calls")).isFalse();
        assertThat(ChatModelToolLoop.isTruncated(null)).isFalse();
    }

    // ────────────── 夹具 ──────────────

    private AgentLoopInput input(StubChatModel model, int maxIterations) {
        return new AgentLoopInput(model, "sys", "user", TOOL, List.of(),
                maxIterations, "run-1-1", 1L, 10L, 1, 3L, null);
    }

    private static ChatResponse text(String text, String finishReason) {
        return new ChatResponse(List.of(generation(
                AssistantMessage.builder().content(text).build(), finishReason)), null);
    }

    private static ChatResponse toolCall(String id, String name, String args, String finishReason) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(generation(message, finishReason)), null);
    }

    private static Generation generation(AssistantMessage message, String finishReason) {
        return finishReason == null
                ? new Generation(message)
                : new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build());
    }

    /** 确定性 ChatModel stub：可编程响应队列。 */
    private static final class StubChatModel implements ChatModel {

        private final Queue<ChatResponse> responses = new LinkedList<>();

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return responses.poll();
        }
    }
}
