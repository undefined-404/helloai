package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.AgentUsage;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REF-8.8 用量互斥拆分 + 耗时专项测试。
 *
 * <p>覆盖：① 互斥分量（输入 / 输出 / 总数）跨步分别累加，且与既有 {@code tokenUsage} 同源一致；
 * ② provider 私有的缓存读 / 缓存写 / 推理分量<b>取不到即留 null</b>（降级不报错，不新增拒绝面）；
 * ③ {@code nativeUsage} 为 Map 时按候选键 best-effort 读取；④ 耗时按墙钟记录。</p>
 */
@DisplayName("REF-8.8 用量互斥拆分 + latency")
class LoopUsageAccountingTest {

    private static final ToolExecutor TOOL = (name, args) -> ToolExecutionResult.success(name, "ok");

    private final AgentLoop loop = new ChatModelToolLoop();

    @Test
    @DisplayName("★互斥分量跨步累加，且与 tokenUsage 同源一致")
    void usageComponentsAccumulateAcrossSteps() {
        StubChatModel model = new StubChatModel()
                .enqueue(toolCallResponse("c1", "t", usage(10, 5, 15, null)))
                .enqueue(textResponse("final", usage(20, 7, 27, null)));

        AgentLoopResult result = loop.run(input(model, 5));

        AgentUsage usage = result.usage();
        assertThat(usage).isNotNull();
        assertThat(usage.inputTokens()).isEqualTo(30);
        assertThat(usage.outputTokens()).isEqualTo(12);
        assertThat(usage.totalTokens()).isEqualTo(42);
        // 既有总数保持同源（既有消费方零改动）
        assertThat(result.tokenUsage()).isEqualTo(42);
        assertThat(usage.latencyMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("provider 未给 usage ⇒ 全分量留 null（降级不报错），耗时仍记录")
    void usageDegradesWhenProviderOmits() {
        StubChatModel model = new StubChatModel().enqueue(textResponse("done"));

        AgentLoopResult result = loop.run(input(model, 5));

        assertThat(result.tokenUsage()).isNull();
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().isEmpty()).isTrue();
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.usage().latencyMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("缓存读 / 缓存写 / 推理分量缺省留 null（provider 私有，取不到即降级）")
    void cacheAndReasoningDefaultNull() {
        StubChatModel model = new StubChatModel()
                .enqueue(textResponse("done", usage(3, 4, 7, null)));

        AgentUsage usage = loop.run(input(model, 5)).usage();

        assertThat(usage.cacheReadTokens()).isNull();
        assertThat(usage.cacheWriteTokens()).isNull();
        assertThat(usage.reasoningTokens()).isNull();
    }

    @Test
    @DisplayName("nativeUsage 为 Map 时按候选键读出缓存 / 推理分量（best-effort）")
    void nativeUsageMapIsReadBestEffort() {
        ChatResponse response = textResponse("done",
                usage(1, 2, 3, Map.of("cached_tokens", 7, "reasoning_tokens", 4)));

        AgentUsage usage = ChatModelToolLoop.accumulateUsage(response, AgentUsage.empty());

        assertThat(usage.inputTokens()).isEqualTo(1);
        assertThat(usage.outputTokens()).isEqualTo(2);
        assertThat(usage.totalTokens()).isEqualTo(3);
        assertThat(usage.cacheReadTokens()).isEqualTo(7);
        assertThat(usage.reasoningTokens()).isEqualTo(4);
        assertThat(usage.cacheWriteTokens()).isNull();
    }

    @Test
    @DisplayName("nativeUsage 非 Map（provider 自有类型）⇒ 私有分量一律 null，不猜测")
    void nonMapNativeUsageIsIgnored() {
        ChatResponse response = textResponse("done", usage(1, 2, 3, "opaque-provider-object"));

        AgentUsage usage = ChatModelToolLoop.accumulateUsage(response, AgentUsage.empty());

        assertThat(usage.cacheReadTokens()).isNull();
        assertThat(usage.cacheWriteTokens()).isNull();
        assertThat(usage.reasoningTokens()).isNull();
    }

    @Test
    @DisplayName("AgentUsage：plus null 安全累加 / withLatency 不改分量 / isEmpty")
    void agentUsageArithmetic() {
        AgentUsage u = AgentUsage.empty().plus(3, null, 3, null, null, null);
        assertThat(u.inputTokens()).isEqualTo(3);
        assertThat(u.outputTokens()).isNull();
        assertThat(u.totalTokens()).isEqualTo(3);

        u = u.plus(null, 5, 5, null, null, null);
        assertThat(u.inputTokens()).isEqualTo(3);
        assertThat(u.outputTokens()).isEqualTo(5);
        assertThat(u.totalTokens()).isEqualTo(8);

        AgentUsage timed = u.withLatency(123L);
        assertThat(timed.latencyMillis()).isEqualTo(123L);
        assertThat(timed.inputTokens()).isEqualTo(3);
        assertThat(timed.isEmpty()).isFalse();
        assertThat(AgentUsage.empty().isEmpty()).isTrue();
    }

    // ────────────── 夹具 ──────────────

    private AgentLoopInput input(StubChatModel model, int maxIterations) {
        return new AgentLoopInput(model, "sys", "user", TOOL, List.of(),
                maxIterations, "run-1-1", 1L, 10L, 1, 3L, null);
    }

    private static DefaultUsage usage(Integer prompt, Integer completion, Integer total, Object nativeUsage) {
        return new DefaultUsage(prompt, completion, total, nativeUsage);
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())), null);
    }

    private static ChatResponse textResponse(String text, DefaultUsage usage) {
        return withUsage(AssistantMessage.builder().content(text).build(), usage);
    }

    private static ChatResponse toolCallResponse(String id, String name, DefaultUsage usage) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                .build();
        return withUsage(message, usage);
    }

    private static ChatResponse withUsage(AssistantMessage message, DefaultUsage usage) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(usage).build());
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
