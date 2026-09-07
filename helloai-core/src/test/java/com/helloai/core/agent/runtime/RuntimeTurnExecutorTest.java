package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
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
 * Runtime 真身单元测试（P0-B）。
 *
 * <p>验证 {@link RuntimeTurnExecutor} 的组装执行契约：Turn 事件骨架成对记录、
 * 沙箱策略观测、输入缺失契约化失败、Loop 失败映射。AgentLoop / 解析器全 mock，
 * 不依赖 Spring 容器与真实 LLM。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RuntimeTurnExecutor")
class RuntimeTurnExecutorTest {

    @Mock
    private AgentLoop agentLoop;

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

    @Mock
    private ChatModel chatModel;

    @Mock
    private AgentEventRecorder eventRecorder;

    @Test
    @DisplayName("完整 Turn：事件骨架成对记录 + 沙箱观测 + 结果透传")
    void shouldExecuteFullTurnWithEventSkeleton() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of("s1"), List.of("s1"), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of(new ToolDefinition("t1", "desc")));
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("final answer", null, 1, 0));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getOutput()).isEqualTo("final answer");

        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(1),
                eqType(AgentEventType.AGENT_STARTED), eqLong(3L), any());
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(5),
                eqType(AgentEventType.SKILL_RESOLVED), eqLong(3L), any());
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(6),
                eqType(AgentEventType.TOOL_RESOLVED), eqLong(3L), any());
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(7),
                eqType(AgentEventType.ENVIRONMENT_RESOLVED), eqLong(3L), any());
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(2),
                eqType(AgentEventType.CONTEXT_BUILT), eqLong(3L), any());
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(0),
                eqType(AgentEventType.AGENT_COMPLETED), eqLong(3L), any());
        verify(sandboxProvider).resolve(new SandboxContext(AgentAccessType.API_KEY_LLM, 1L, 10L, 3L));
    }

    @Test
    @DisplayName("输入缺失（chatModel/prompt 为空）→ 契约化失败，不执行 Loop 不记录事件")
    void shouldFailWhenInputMissing() {
        AgentContext ctx = AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).agentId(3L)
                .systemPrompt("sys").userPrompt("user")
                .build();

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getOutput()).contains("不可为空");
        verifyNoInteractions(agentLoop, eventRecorder, sandboxProvider);
    }

    @Test
    @DisplayName("Loop 失败（MAX_ITERATIONS/ERROR）→ FAILED 结果映射")
    void shouldMapLoopFailureToFailedResult() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.error("boom", 3, 2));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("boom");
    }

    @Test
    @DisplayName("事件记录器为空 → 事件跳过但 Turn 正常执行")
    void shouldSkipEventsWhenRecorderNull() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));
        AgentContext ctx = AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).agentId(3L)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .build();

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        verifyNoInteractions(eventRecorder);
    }

    private AgentContext context() {
        return AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .skills(List.of("s1")).tools(List.of("t1"))
                .environment(new LocalProcessEnvironment())
                .accessType(AgentAccessType.API_KEY_LLM)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .eventRecorder(eventRecorder)
                .build();
    }

    // ---- 参数匹配器别名（record 签名含原始类型，简化断言可读性） ----
    private static String eqRun(String v) { return org.mockito.ArgumentMatchers.eq(v); }
    private static Long eqLong(Long v) { return org.mockito.ArgumentMatchers.eq(v); }
    private static int eqInt(int v) { return org.mockito.ArgumentMatchers.eq(v); }
    private static AgentEventType eqType(AgentEventType v) { return org.mockito.ArgumentMatchers.eq(v); }
}
