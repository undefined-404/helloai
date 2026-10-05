package com.helloai.core.agent.runtime;

import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.impl.ChatModelToolLoop;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L3-b 误杀边界反证（秦戈 · 临时验证用，test-only，不入生产 且 **不 commit**）。
 *
 * <p><b>目的</b>：护栏 A 判据 = {@code success && 正文空白 && toolCallCount()==0 ⇒ FAILED}。
 * 真正要反证的是「<b>先返回工具调用、再返回空正文</b>」这一真实序列下，工具调用数被<b>整轮累计</b>
 * （{@code ChatModelToolLoop:72} 循环外声明、{@code :103} 循环内累加），于是 {@code toolCallCount>0}
 * ⇒ **仍判 SUCCESS、不被误杀**。</p>
 *
 * <p>关键：本测试用**真实的** {@link ChatModelToolLoop} + **真实的** {@link RuntimeTurnExecutor}
 * （非 mock {@code AgentLoopResult}），仅 {@link ChatModel} 是确定性 stub —— 消除「单测是 mock、
 * 没验证真实累加逻辑」的质疑。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("L3-b 误杀边界反证：工具调用+空正文 → SUCCESS（真实 ChatModelToolLoop + RuntimeTurnExecutor）")
class RuntimeEmptyTextToolsBoundaryTest {

    @Mock
    private ToolExecutor toolExecutor;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private AgentSkillSpecService agentSkillSpecService;
    @Mock
    private SandboxProvider sandboxProvider;
    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    /** ① 循环层：整轮累计 toolCallCount —— 工具调用后空正文 ⇒ toolCallCount=1、text="". */
    @Test
    @DisplayName("loop 层：第1轮工具调用 + 第2轮空正文 ⇒ toolCallCount=1、text 空、success")
    void loopLevel_toolsThenBlankText_countsCumulative() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{}"))
                .enqueue(responseWithBlankText());
        when(toolExecutor.execute("echo", "{}")).thenReturn(ToolExecutionResult.success("echo", "ok"));

        AgentLoopResult result = new ChatModelToolLoop().run(new AgentLoopInput(
                model, "sys", "user", toolExecutor, List.<ToolCallback>of(),
                AgentLoopInput.DEFAULT_MAX_ITERATIONS, "run-1-1", 1L, 10L, 1, 3L, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEmpty();          // 末轮正文空
        assertThat(result.toolCallCount()).isEqualTo(1); // 整轮累计（非末轮）
    }

    /** ② 真身层（★核心）：真实 ChatModelToolLoop + 真实 RuntimeTurnExecutor，空正文但有工具调用 ⇒ SUCCESS。 */
    @Test
    @DisplayName("★Runtime 真身：工具调用+空正文 ⇒ ExecutionStatus.SUCCESS（A 不误杀），绝不 FAILED")
    void runtimeTurnExecutor_toolsThenBlankText_isSuccess() {
        StubChatModel model = new StubChatModel()
                .enqueue(responseWithToolCall("tc1", "echo", "{}"))
                .enqueue(responseWithBlankText());
        when(toolExecutor.execute("echo", "{}")).thenReturn(ToolExecutionResult.success("echo", "ok"));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        // 注：enabledTools 为空 ⇒ RuntimeTurnExecutor.resolveEnabledCallbacks 提前返回，不会触达 toolCallbackProvider
        //     （故此处不 stub，避免 Mockito unnecessary-stubbing）。

        RuntimeTurnExecutor executor = new RuntimeTurnExecutor(
                new ChatModelToolLoop(), toolExecutor, toolRegistry, agentSkillSpecService,
                sandboxProvider, toolCallbackProvider);

        AgentExecutionResult result = executor.execute(AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .systemPrompt("sys").userPrompt("use the tool, output no text")
                .chatModel(model)
                .accessType(null)                    // 跳过沙箱观测
                .eventRecorder(null)                 // 事件 write-only
                .build());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS); // ★不得被 A 判 FAILED
        assertThat(result.getOutput()).isEmpty();
    }

    // ---- 确定性 ChatModel stub（镜像 ChatModelToolLoopTest.StubChatModel） ----

    private static ChatResponse responseWithToolCall(String id, String name, String args) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
        return new ChatResponse(List.of(new Generation(message)), null);
    }

    private static ChatResponse responseWithBlankText() {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").build())), null);
    }

    private static final class StubChatModel implements ChatModel {
        private final Queue<ChatResponse> responses = new LinkedList<>();

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<Message> unused = new ArrayList<>(prompt.getInstructions());
            return responses.poll();
        }
    }
}
