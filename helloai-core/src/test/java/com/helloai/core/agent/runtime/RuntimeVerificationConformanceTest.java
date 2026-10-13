package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.runtime.loop.impl.ChatModelToolLoop;
import com.helloai.core.agent.runtime.loop.impl.ContextCompactor;
import com.helloai.core.agent.runtime.loop.impl.FinalReportFenceHook;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * REF-8.4 ④ 出口分类：内部 LLM 围栏合格性 —— 真实 ChatModelToolLoop + 真实 RuntimeTurnExecutor。
 *
 * <p>红线①反证：只改 ExecutionStatus，不碰 SubTaskStatus（RuntimeTurnExecutor 只返回
 * {@link AgentExecutionResult}，本类恒不触达 subTask 域）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("REF-8.4 ④ 出口分类：内部 LLM 围栏合格性")
class RuntimeVerificationConformanceTest {

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

    private RuntimeTurnExecutor executor() {
        return new RuntimeTurnExecutor(new ChatModelToolLoop(), toolExecutor, toolRegistry,
                agentSkillSpecService, sandboxProvider, toolCallbackProvider);
    }

    @Test
    @DisplayName("内部 LLM 自然 STOP 缺 VERIFICATION 围栏 ⇒ FAILED")
    void internalMissingFence_failsFast() {
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));

        AgentExecutionResult result = executor().execute(AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .systemPrompt("sys").userPrompt("deliver the summary")
                .chatModel(stub(responseWithText("done, no evidence")))
                .accessType(AgentAccessType.API_KEY_LLM)
                .eventRecorder(null)
                .build());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getOutput()).contains("missing_verification");
    }

    @Test
    @DisplayName("内部 LLM 自然 STOP 携带 VERIFICATION 围栏 ⇒ SUCCESS")
    void internalWithFence_succeeds() {
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));

        AgentExecutionResult result = executor().execute(AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .systemPrompt("sys").userPrompt("deliver the summary")
                .chatModel(stub(responseWithText("完成\nVERIFICATION:\n- 已核对输出示例\nSTATUS: DONE\nNEXT_STEPS: 无")))
                .accessType(AgentAccessType.API_KEY_LLM)
                .eventRecorder(null)
                .build());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getOutput()).contains("VERIFICATION:");
    }

    @Test
    @DisplayName("内部 LLM 缺围栏 + 收尾轮自纠出围栏 ⇒ SUCCESS（② + ④ 组合）")
    void internalMissingFence_withFinalizeHook_succeeds() {
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));

        // 循环挂收尾轮：草稿缺围栏 → 收尾轮自纠出围栏 → 出口分类 ④ 不再 fail-close
        RuntimeTurnExecutor executor = new RuntimeTurnExecutor(
                new ChatModelToolLoop(ContextCompactor.noop(), List.of(new FinalReportFenceHook())),
                toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider);
        Queue<ChatResponse> queue = new LinkedList<>();
        queue.offer(responseWithText("done, no evidence"));
        queue.offer(responseWithText("done\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无"));

        AgentExecutionResult result = executor.execute(AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .systemPrompt("sys").userPrompt("deliver")
                .chatModel(stub(queue))
                .accessType(AgentAccessType.API_KEY_LLM)
                .eventRecorder(null)
                .build());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getOutput()).contains("VERIFICATION:");
    }

    @Test
    @DisplayName("非内部（null accessType）缺 VERIFICATION 围栏 ⇒ 不被本护栏拦（SUCCESS）")
    void externalMissingFence_notGated() {
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));

        AgentExecutionResult result = executor().execute(AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .systemPrompt("sys").userPrompt("deliver")
                .chatModel(stub(responseWithText("done, no evidence")))
                .accessType(null)
                .eventRecorder(null)
                .build());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    private static ChatModel stub(ChatResponse response) {
        Queue<ChatResponse> responses = new LinkedList<>();
        responses.offer(response);
        return stub(responses);
    }

    private static ChatModel stub(Queue<ChatResponse> responses) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return responses.poll();
            }
        };
    }

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(text).build())), null);
    }
}