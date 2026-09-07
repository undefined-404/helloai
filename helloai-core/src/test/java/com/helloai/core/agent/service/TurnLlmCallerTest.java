package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Turn LLM 调用器单元测试（P0-B-2 主链接线注入）。
 *
 * <p>验证 {@link TurnLlmCaller} 的 Legacy ↔ Runtime 切换：runtime-enabled=false →
 * 单次调用（现状零变化）；true → AgentLoop 循环（ChatModel + ToolExecutor，结果映射
 * success/failure）；异常降级不抛。AgentLoop / ChatModel / 解析器全 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TurnLlmCaller")
class TurnLlmCallerTest {

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private AgentChatClientService agentChatClientService;

    @Mock
    private AgentLlmCredentialResolver agentLlmCredentialResolver;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private AgentLoop agentLoop;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    @Mock
    private ChatModel chatModel;

    @Mock
    private AgentEventRecorder eventRecorder;

    @Mock
    private Agent agent;

    @Test
    @DisplayName("runtime-enabled=false（默认）→ Legacy 单次调用，不触达 AgentLoop")
    void shouldUseLegacySingleCallWhenDisabled() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(false);
        AgentTask task = AgentTask.builder().subTaskId(10L).userPrompt("user prompt").build();
        TurnLlmCallContext ctx = context(task);
        when(platformAgentExecutionService.executeSync(agent, task))
                .thenReturn(AgentResult.success("legacy output", "STOP", "LegacyExecutor", 1));

        AgentResult result = caller().call(ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("legacy output");
        verify(platformAgentExecutionService).executeSync(agent, task);
        verifyNoInteractions(agentLoop);
    }

    @Test
    @DisplayName("runtime-enabled=true → AgentLoop 循环（mock 模式）并映射结果")
    void shouldUseRuntimeLoopWhenEnabled() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(true);
        when(executionProperties.isMockMode()).thenReturn(true);
        when(agentChatClientService.buildChatModel(any(), any(), any())).thenReturn(chatModel);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("final answer", null, 1, 0));

        AgentResult result = caller().call(context(AgentTask.builder().subTaskId(10L).userPrompt("u").build()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("final answer");
        assertThat(result.getExecutorName()).isEqualTo("RuntimeAgentLoop");
        verify(agentLoop).run(any());
    }

    @Test
    @DisplayName("runtime-enabled=true + 循环失败 → AgentResult.failure（best-effort）")
    void shouldMapLoopFailureToResult() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(true);
        when(executionProperties.isMockMode()).thenReturn(true);
        when(agentChatClientService.buildChatModel(any(), any(), any())).thenReturn(chatModel);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.error("boom", 3, 2));

        AgentResult result = caller().call(context(AgentTask.builder().subTaskId(10L).userPrompt("u").build()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("runtime-enabled=true + 构建/解析异常 → failure 不抛（契约化）")
    void shouldCatchRuntimeException() {
        when(executionProperties.isRuntimeEnabled()).thenReturn(true);
        when(executionProperties.isMockMode()).thenReturn(false);
        when(agentChatClientService.buildChatModel(any(), any(), any()))
                .thenThrow(new BizException("no api key"));

        AgentResult result = caller().call(context(AgentTask.builder().subTaskId(10L).userPrompt("u").build()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("no api key");
    }

    private TurnLlmCallContext context(AgentTask task) {
        return new TurnLlmCallContext(agent, task, List.of("echo"), "run-1-1", 1L, 10L, 1, eventRecorder);
    }

    private TurnLlmCaller caller() {
        return new TurnLlmCaller(executionProperties, agentChatClientService, agentLlmCredentialResolver,
                platformAgentExecutionService, agentLoop, toolExecutor, toolCallbackProvider);
    }
}
