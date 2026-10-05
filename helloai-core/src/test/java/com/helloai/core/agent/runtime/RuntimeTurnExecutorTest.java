package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.LoopCheckpointListener;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Map;

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
    @DisplayName("A 空产出护栏：成功终态但正文空白 + 零工具调用 ⇒ FAILED（可重试），非 SUCCESS")
    void shouldFailWhenSuccessButEmptyOutputWithoutTools() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        // 复刻现场：finishReason=STOP、iterations=1、toolCalls=0、tokens 很大但正文为空
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("", null, 1, 0, 83958));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getOutput()).contains("agent_runtime_empty_output");
        // tokenUsage 仍透传（诊断用），思维链若存在也随失败透传
        assertThat(result.getTokenUsage()).isEqualTo(83958);
    }

    @Test
    @DisplayName("A 空产出护栏：纯空白字符正文（StringUtils.isBlank 口径）同样判 FAILED")
    void shouldFailWhenSuccessButWhitespaceOnlyOutput() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("  \n\t  ", "思考过程", 1, 0, null));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        // B 配套：思维链在 FAILED 结果上仍保留（供失败路径落 conversation_message）
        assertThat(result.getThinking()).isEqualTo("思考过程");
    }

    @Test
    @DisplayName("A 防误伤：正文空白但**有工具调用** ⇒ 仍 SUCCESS（靠工具/附件交付产物的合法成功）")
    void shouldKeepSuccessWhenEmptyTextButToolsInvoked() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("", null, 3, 2, 100));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getOutput()).isEmpty();
        assertThat(result.getTokenUsage()).isEqualTo(100);
    }

    @Test
    @DisplayName("tokenUsage 映射：loop 累加值透传到结果 + AGENT_COMPLETED payload（成功）")
    void shouldMapTokenUsageOnSuccess() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 2, 1, 42));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getTokenUsage()).isEqualTo(42);
        org.mockito.ArgumentCaptor<Map<String, Object>> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(0),
                eqType(AgentEventType.AGENT_COMPLETED), eqLong(3L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("tokens", 42);
    }

    @Test
    @DisplayName("tokenUsage 映射：失败终态（MAX_ITERATIONS 部分消耗）同样携带累加值")
    void shouldMapTokenUsageOnFailure() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.maxIterations("cut", 5, 4, 30));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getTokenUsage()).isEqualTo(30);
    }

    @Test
    @DisplayName("checkpoint：上下文携带 listener → 透传进 AgentLoopInput（同实例）")
    void shouldPassLoopCheckpointListenerIntoAgentLoopInput() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));
        LoopCheckpointListener listener = checkpoint -> { };
        AgentContext ctx = AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).agentId(3L)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .loopCheckpointListener(listener)
                .build();

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(ctx);

        ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
        verify(agentLoop).run(captor.capture());
        assertThat(captor.getValue().loopCheckpointListener()).isSameAs(listener);
    }

    @Test
    @DisplayName("checkpoint：上下文未携带 listener → 透传 null（不落检查点）")
    void shouldPassNullLoopCheckpointListenerWhenAbsent() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
        verify(agentLoop).run(captor.capture());
        assertThat(captor.getValue().loopCheckpointListener()).isNull();
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

    @Test
    @DisplayName("G-004 增量 A：SKILL_RESOLVED payload 携带命中技能版本（resolvedVersions）")
    void shouldRecordSkillResolvedWithVersions() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(
                        List.of("s1"), List.of("s1"), "", Map.of("s1", "1.0.0"), List.of()));
        when(toolRegistry.resolve(any())).thenReturn(List.of());
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        org.mockito.ArgumentCaptor<Map<String, Object>> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(5),
                eqType(AgentEventType.SKILL_RESOLVED), eqLong(3L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("requiredSkills", List.of("s1"))
                .containsEntry("resolvedSpecs", List.of("s1"))
                .containsEntry("resolvedVersions", Map.of("s1", "1.0.0"));
    }

    @Test
    @DisplayName("G-004 增量 A：启用工具 = 上下文 tools ∪ 技能 requiredTools（并集去重保序）")
    void shouldMergeSkillRequiredToolsIntoEnabledTools() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(
                        List.of("s1"), List.of("s1"), "", Map.of("s1", "1.0.0"), List.of("t2", "t3")));
        when(toolRegistry.resolve(List.of("t1", "t2", "t3")))
                .thenReturn(List.of(new ToolDefinition("t3", "skill tool")));
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        // Registry 收到并集（上下文工具在前、技能声明工具按 resolve 序追加）
        verify(toolRegistry).resolve(List.of("t1", "t2", "t3"));
        // TOOL_RESOLVED payload 呈现并集
        org.mockito.ArgumentCaptor<Map<String, Object>> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(6),
                eqType(AgentEventType.TOOL_RESOLVED), eqLong(3L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("tools", List.of("t1", "t2", "t3"));
    }

    @Test
    @DisplayName("G-004 增量 A：无技能声明工具时启用清单与上下文一致（兼容回归）")
    void shouldKeepEnabledToolsUnchangedWhenSkillsDeclareNone() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(
                        List.of("s1"), List.of("s1"), "", Map.of("s1", "1.0.0"), List.of()));
        when(toolRegistry.resolve(List.of("t1"))).thenReturn(List.of());
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        verify(toolRegistry).resolve(List.of("t1"));
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
