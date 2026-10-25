package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.LoopCheckpoint;
import com.helloai.core.agent.runtime.loop.LoopCheckpointListener;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 循环真身单元测试（P0-C）。
 *
 * <p>以确定性 {@link StubChatModel}（可编程响应队列 + 记录最近 messages）验证
 * {@link ChatModelToolLoop} 的手动工具循环契约：终态判定 / 工具执行与 Observation 回喂 /
 * 错误回喂 / 循环终止 / TOOL_CALL 事件记录 / 循环检查点回调（每轮触发与 write-only 容错）/
 * 入参缺失降级。纯 Mockito + 自研 stub，不依赖 Spring 容器与真实 LLM。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatModelToolLoop")
class ChatModelToolLoopTest {

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private AgentEventRecorder eventRecorder;

    private final AgentLoop loop = new ChatModelToolLoop();

    @Test
    @DisplayName("单轮无工具调用 → 终态文本，STOP，不触达工具执行器")
    void shouldReturnFinalTextInSingleIteration() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithText("final answer"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("final answer");
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.toolCallCount()).isZero();
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(result.errorMessage()).isNull();
        verifyNoInteractions(toolExecutor);
        verifyNoInteractions(eventRecorder);
    }

    @Test
    @DisplayName("空 systemPrompt → 不发送空 SystemMessage，仅剩 UserMessage")
    void shouldSkipBlankSystemMessage() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop.run(new AgentLoopInput(model, "", "user", toolExecutor, List.of(),
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, eventRecorder));

        List<Message> sent = model.lastMessages();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(UserMessage.class);
        assertThat(sent).noneMatch(m -> m instanceof SystemMessage);
    }

    @Test
    @DisplayName("null systemPrompt → 同样不发送空 SystemMessage（provider 会 400）")
    void shouldSkipNullSystemMessage() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop.run(new AgentLoopInput(model, null, "user", toolExecutor, List.of(),
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, eventRecorder));

        List<Message> sent = model.lastMessages();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(UserMessage.class);
        assertThat(sent).noneMatch(m -> m instanceof SystemMessage);
    }

    @Test
    @DisplayName("非空 systemPrompt → 首位仍为 SystemMessage 且内容一致（不误伤正常链路）")
    void shouldKeepNonBlankSystemMessage() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("ok"));

        loop.run(input(model));

        List<Message> sent = model.lastMessages();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(sent.get(0).getText()).isEqualTo("sys");
        assertThat(sent.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("工具调用 → 执行并回喂 Observation → 终态文本；TOOL_CALL 事件成对记录")
    void shouldExecuteToolAndFeedBackObservation() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{\"message\":\"hi\"}"))
                .enqueue(responseWithText("done"));
        when(toolExecutor.execute("echo", "{\"message\":\"hi\"}"))
                .thenReturn(ToolExecutionResult.success("echo", "{\"ok\":true,\"echoed\":\"hi\"}"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.finishReason()).isEqualTo("STOP");

        // Observation 已以 ToolResponseMessage 回喂：最后一次调用 messages 末位即工具响应
        Message last = model.lastMessages().get(model.lastMessages().size() - 1);
        assertThat(last).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResponse = (ToolResponseMessage) last;
        assertThat(toolResponse.getResponses()).hasSize(1);
        assertThat(toolResponse.getResponses().get(0).name()).isEqualTo("echo");
        assertThat(toolResponse.getResponses().get(0).responseData()).isEqualTo("{\"ok\":true,\"echoed\":\"hi\"}");

        verify(eventRecorder).record(eq("run-1-1"), eq(1L), eq(10L), eq(1), eq(3),
                eq(AgentEventType.TOOL_CALL_STARTED), eq(3L), any());
        verify(eventRecorder).record(eq("run-1-1"), eq(1L), eq(10L), eq(1), eq(4),
                eq(AgentEventType.TOOL_CALL_COMPLETED), eq(3L), any());
    }

    @Test
    @DisplayName("★Anthropic 协议：正文与 tool_use 分居两个 Generation ⇒ 仍执行工具并回喂（2026-10-25 事故回归）")
    void shouldExecuteToolWhenAnthropicSplitsTextAndToolUseIntoTwoGenerations() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithSplitToolCall("toolu_1", "internal_get_sub_task_detail", "{}"))
                .enqueue(responseWithText("done"));
        when(toolExecutor.execute("internal_get_sub_task_detail", "{}"))
                .thenReturn(ToolExecutionResult.success("internal_get_sub_task_detail", "{\"title\":\"x\"}"));

        AgentLoopResult result = loop.run(input(model));

        // 事故现场：只读 getResult()（第 0 个=正文）⇒ toolCalls 空 ⇒ 1 轮 STOP、0 次调用
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.toolCallCount()).isEqualTo(1);
        assertThat(result.finishReason()).isEqualTo("STOP");

        List<Message> sent = model.lastMessages();
        // 历史里的 assistant 消息必须同时带正文与 tool_use，否则下一轮 tool_result 无主（provider 会拒答）
        AssistantMessage assistant = sent.stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(assistant.getToolCalls().get(0).name()).isEqualTo("internal_get_sub_task_detail");
        assertThat(assistant.getText()).isEqualTo("我先查一下子任务详情。");
        // 末位仍是工具响应（回喂顺序未被打乱）
        assertThat(sent.get(sent.size() - 1)).isInstanceOf(ToolResponseMessage.class);
    }

    @Test
    @DisplayName("TOOL_CALL 事件 payload 带 callId（与模型 tool_call.id 一致，供调用/返回硬关联）")
    @SuppressWarnings("unchecked")
    void shouldRecordCallIdInToolCallEvents() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("call-abc-123", "echo", "{\"message\":\"hi\"}"))
                .enqueue(responseWithText("done"));
        when(toolExecutor.execute("echo", "{\"message\":\"hi\"}"))
                .thenReturn(ToolExecutionResult.success("echo", "ok"));

        loop.run(input(model));

        ArgumentCaptor<Map<String, Object>> payloads = ArgumentCaptor.forClass(Map.class);
        verify(eventRecorder, times(2)).record(eq("run-1-1"), eq(1L), eq(10L), eq(1), anyInt(),
                any(AgentEventType.class), eq(3L), payloads.capture());
        // started 与 completed 都必须带同一个 callId（否则前端无法配对耗时）
        assertThat(payloads.getAllValues()).hasSize(2);
        assertThat(payloads.getAllValues().get(0)).containsEntry("callId", "call-abc-123");
        assertThat(payloads.getAllValues().get(1)).containsEntry("callId", "call-abc-123");
    }

    @Test
    @DisplayName("工具执行失败 → ERROR 回喂模型，循环继续并终态")
    void shouldFeedBackToolErrorAndContinue() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{}"))
                .enqueue(responseWithText("recovered"));
        when(toolExecutor.execute("echo", "{}"))
                .thenReturn(ToolExecutionResult.failure("echo", "boom"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);
        ToolResponseMessage toolResponse = (ToolResponseMessage)
                model.lastMessages().get(model.lastMessages().size() - 1);
        assertThat(toolResponse.getResponses().get(0).responseData()).isEqualTo("ERROR: boom");
    }

    @Test
    @DisplayName("达到 maxIterations 仍无终态 → MAX_ITERATIONS（防死循环）")
    void shouldStopAtMaxIterations() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < AgentLoopInput.DEFAULT_MAX_ITERATIONS; i++) {
            model.enqueue(responseWithToolCall("tc" + i, "echo", "{}"));
        }
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("echo", "x"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isFalse();
        assertThat(result.finishReason()).isEqualTo("MAX_ITERATIONS");
        assertThat(result.iterations()).isEqualTo(AgentLoopInput.DEFAULT_MAX_ITERATIONS);
        assertThat(result.errorMessage()).contains("max iterations");
    }

    @Test
    @DisplayName("tokenUsage：多轮 usage 累加（工具轮 + 终态轮 totalTokens 求和）")
    void shouldAccumulateTokenUsageAcrossIterations() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCallAndUsage("tc1", "echo", "{}", 10))
                .enqueue(responseWithTextAndUsage("done", 15));
        when(toolExecutor.execute("echo", "{}")).thenReturn(ToolExecutionResult.success("echo", "o"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.tokenUsage()).isEqualTo(25);
    }

    @Test
    @DisplayName("tokenUsage：usage 缺失（metadata null）→ 恒 null，不阻断循环")
    void shouldReturnNullTokenUsageWhenUsageMissing() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("final"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.tokenUsage()).isNull();
    }

    @Test
    @DisplayName("tokenUsage：部分轮 usage 缺失 → 只累加存在的轮次")
    void shouldAccumulateOnlyPresentUsage() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCallAndUsage("tc1", "echo", "{}", 10))
                .enqueue(responseWithText("done"));
        when(toolExecutor.execute("echo", "{}")).thenReturn(ToolExecutionResult.success("echo", "o"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.tokenUsage()).isEqualTo(10);
    }

    @Test
    @DisplayName("tokenUsage：MAX_ITERATIONS 终态携带已发生轮次累加值")
    void shouldCarryTokenUsageOnMaxIterations() {
        StubChatModel model = new StubChatModel();
        for (int i = 0; i < AgentLoopInput.DEFAULT_MAX_ITERATIONS; i++) {
            model.enqueue(responseWithToolCallAndUsage("tc" + i, "echo", "{}", 3));
        }
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("echo", "x"));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.finishReason()).isEqualTo("MAX_ITERATIONS");
        assertThat(result.tokenUsage()).isEqualTo(AgentLoopInput.DEFAULT_MAX_ITERATIONS * 3);
    }

    @Test
    @DisplayName("chatModel / toolExecutor 缺失 → ERROR 结果（best-effort 不抛）")
    void shouldFailWhenRequiredInputMissing() {
        AgentLoopInput bad = new AgentLoopInput(null, "s", "u", null, List.of(),
                5, "run-1-1", 1L, 10L, 1, 3L, eventRecorder);

        AgentLoopResult result = loop.run(bad);

        assertThat(result.success()).isFalse();
        assertThat(result.finishReason()).isEqualTo("ERROR");
        assertThat(result.errorMessage()).contains("requires");
    }

    @Test
    @DisplayName("事件记录器为空 → 工具调用不记录事件但循环正常")
    void shouldSkipEventRecordingWhenRecorderNull() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{}"))
                .enqueue(responseWithText("ok"));
        when(toolExecutor.execute("echo", "{}")).thenReturn(ToolExecutionResult.success("echo", "o"));

        AgentLoopInput input = new AgentLoopInput(model, "s", "u", toolExecutor, List.of(),
                5, "run-1-1", 1L, 10L, 1, 3L, null);

        AgentLoopResult result = loop.run(input);

        assertThat(result.success()).isTrue();
        assertThat(result.toolCallCount()).isEqualTo(1);
        verifyNoInteractions(eventRecorder);
    }

    @Test
    @DisplayName("模型返回空工具调用列表（异常响应）→ 按终态返回，不循环")
    void shouldTreatEmptyToolCallListAsTerminal() {
        StubChatModel model = new StubChatModel();
        AssistantMessage emptyToolCalls = AssistantMessage.builder().content("").toolCalls(List.of()).build();
        model.enqueue(new ChatResponse(List.of(new Generation(emptyToolCalls)), null));

        AgentLoopResult result = loop.run(input(model));

        assertThat(result.success()).isTrue();
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.toolCallCount()).isZero();
        verify(toolExecutor, never()).execute(anyString(), anyString());
    }

    @Test
    @DisplayName("checkpoint：每个工具轮结束后回调一次（iteration/计数/已执行工具累积）")
    void shouldFireCheckpointAfterEachToolIteration() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{}"))
                .enqueue(responseWithToolCall("tc2", "calc", "{}"))
                .enqueue(responseWithText("done"));
        when(toolExecutor.execute(anyString(), anyString()))
                .thenReturn(ToolExecutionResult.success("t", "o"));
        List<LoopCheckpoint> checkpoints = new ArrayList<>();

        AgentLoopResult result = loop.run(input(model, checkpoints::add));

        assertThat(result.success()).isTrue();
        assertThat(checkpoints).hasSize(2);
        assertThat(checkpoints.get(0).iteration()).isEqualTo(1);
        assertThat(checkpoints.get(0).toolCallCount()).isEqualTo(1);
        assertThat(checkpoints.get(0).messageCount()).isEqualTo(4);
        assertThat(checkpoints.get(0).executedToolNames()).containsExactly("echo");
        assertThat(checkpoints.get(1).iteration()).isEqualTo(2);
        assertThat(checkpoints.get(1).toolCallCount()).isEqualTo(2);
        assertThat(checkpoints.get(1).messageCount()).isEqualTo(6);
        assertThat(checkpoints.get(1).executedToolNames()).containsExactly("echo", "calc");
    }

    @Test
    @DisplayName("checkpoint：终态轮（无工具调用）不回调")
    void shouldNotFireCheckpointOnTerminalIteration() {
        StubChatModel model = new StubChatModel().enqueue(responseWithText("final"));
        LoopCheckpointListener listener = mock(LoopCheckpointListener.class);

        AgentLoopResult result = loop.run(input(model, listener));

        assertThat(result.success()).isTrue();
        verifyNoInteractions(listener);
    }

    @Test
    @DisplayName("checkpoint：回调抛异常不阻断循环（write-only 容错）")
    void shouldContinueLoopWhenCheckpointListenerThrows() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{}"))
                .enqueue(responseWithText("ok"));
        when(toolExecutor.execute("echo", "{}")).thenReturn(ToolExecutionResult.success("echo", "o"));

        AgentLoopResult result = loop.run(input(model, checkpoint -> {
            throw new RuntimeException("sink down");
        }));

        assertThat(result.success()).isTrue();
        assertThat(result.toolCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("兼容构造器（12 参）：listener 缺省 null（不落检查点）")
    void shouldDefaultListenerToNullInLegacyConstructor() {
        AgentLoopInput legacy = input(new StubChatModel());

        assertThat(legacy.loopCheckpointListener()).isNull();
    }

    @Test
    @DisplayName("REF-8.4 挂点骨架：附加挂点被分发（请求前 + 终止前）且不影响结果")
    void shouldDispatchRegisteredHooks() {
        LoopHook extraHook = mock(LoopHook.class);
        AgentLoop loopWithHook = new ChatModelToolLoop(ContextCompactor.noop(), List.of(extraHook));
        StubChatModel model = new StubChatModel().enqueue(responseWithText("done"));

        AgentLoopResult result = loopWithHook.run(input(model));

        verify(extraHook).beforeRequest(anyList(), any(), any(), eq(0));
        verify(extraHook).beforeTerminate(any(AgentLoopTermination.class));
        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("done");
    }

    private AgentLoopInput input(StubChatModel model) {
        return new AgentLoopInput(model, "sys", "user", toolExecutor, List.of(),
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, eventRecorder);
    }

    private AgentLoopInput input(StubChatModel model, LoopCheckpointListener listener) {
        return new AgentLoopInput(model, "sys", "user", toolExecutor, List.of(),
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, eventRecorder, listener);
    }

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())), null);
    }

    private static ChatResponse responseWithTextAndUsage(String text, int totalTokens) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(1, 1, totalTokens))
                        .build());
    }

    private static ChatResponse responseWithToolCall(String id, String name, String args) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)), null);
    }

    /**
     * 复刻 {@code AnthropicChatModel.toChatResponse} 的真实形态：
     * 正文在 Generation[0]（无工具调用），{@code tool_use} 追加为 Generation[1]。
     */
    private static ChatResponse responseWithSplitToolCall(String id, String name, String args) {
        AssistantMessage textOnly = AssistantMessage.builder().content("我先查一下子任务详情。").build();
        AssistantMessage toolOnly = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(textOnly), new Generation(toolOnly)), null);
    }

    private static ChatResponse responseWithToolCallAndUsage(String id, String name, String args, int totalTokens) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(1, 1, totalTokens))
                        .build());
    }

    /** 确定性 ChatModel stub：可编程响应队列 + 记录最近一次调用收到的 messages。 */
    private static final class StubChatModel implements ChatModel {

        private final Queue<ChatResponse> responses = new LinkedList<>();
        private List<Message> lastMessages = new ArrayList<>();

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastMessages = new ArrayList<>(prompt.getInstructions());
            return responses.poll();
        }

        List<Message> lastMessages() {
            return lastMessages;
        }
    }
}
