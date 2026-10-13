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
import com.helloai.core.agent.tool.ToolContext;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    /** 内部 LLM 自证围栏后缀（对齐 ④ 出口分类契约：自然 STOP 报告须携带 VERIFICATION 才算自证完成）。 */
    private static final String FENCED_REPORT_SUFFIX =
            "\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无";

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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of(new ToolDefinition("t1", "desc")));
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        // 内部 LLM（context 为 API_KEY_LLM）：自然 STOP 报告须自证 VERIFICATION 围栏才走 SUCCESS（④ 契约）
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop(
                "final answer" + FENCED_REPORT_SUFFIX, null, 1, 0));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getOutput()).isEqualTo("final answer" + FENCED_REPORT_SUFFIX);

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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("  \n\t  ", "思考过程", 1, 0, null));

        AgentExecutionResult result = new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        // B 配套：思维链在 FAILED 结果上仍保留（供失败路径落 conversation_message）
        assertThat(result.getThinking()).isEqualTo("思考过程");
    }

    @Test
    @DisplayName("口径 A：内部 LLM 空正文但有工具 ⇒ SUCCESS（空交给 Guard A；④ 不因空判失败，质量靠 reviewer 大环兜底）")
    void shouldKeepSuccessWhenEmptyTextButToolsInvoked() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        // Guard A 只判「空正文 && 0 工具」⇒ FAILED；此处 tools=2 ⇒ 空正文不被 Guard A 拦，
        // ④ 口径 A 显式跳过空正文（!isBlank）⇒ 不走「缺围栏」判失败 ⇒ SUCCESS（质量交 reviewer）。
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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop(
                "ok" + FENCED_REPORT_SUFFIX, null, 2, 1, 42));

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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
    @DisplayName("REF-8.2：内部 LLM（API_KEY_LLM）maxIterations 取内部分档（配置缺失回落默认 10）")
    void shouldUseHigherMaxIterationsForInternalLlm() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        // 内部 LLM：executionProperties 未注入（null）→ 回落 DEFAULT_MAX_ITERATIONS_EXTRA=10
        AgentContext internalCtx = AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).agentId(3L)
                .accessType(AgentAccessType.API_KEY_LLM)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .build();
        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(internalCtx);
        assertThat(captureLoopInput().maxIterations())
                .isEqualTo(com.helloai.core.agent.runtime.loop.AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA);
    }

    @Test
    @DisplayName("REF-8.2：外部 Agent（CLI_CLIENT）maxIterations 回落外部默认 5")
    void shouldUseDefaultMaxIterationsForExternalLlm() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        AgentContext externalCtx = AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).agentId(3L)
                .accessType(AgentAccessType.CLI_CLIENT)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .build();
        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(externalCtx);
        assertThat(captureLoopInput().maxIterations())
                .isEqualTo(com.helloai.core.agent.runtime.loop.AgentLoopInput.DEFAULT_MAX_ITERATIONS);
    }

    @Test
    @DisplayName("checkpoint：上下文未携带 listener → 透传 null（不落检查点）")
    void shouldPassNullLoopCheckpointListenerWhenAbsent() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
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
        when(toolRegistry.resolve(eq(List.of("t1", "t2", "t3")), any()))
                .thenReturn(List.of(new ToolDefinition("t3", "skill tool")));
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        // Registry 收到并集（上下文工具在前、技能声明工具按 resolve 序追加）
        verify(toolRegistry).resolve(eq(List.of("t1", "t2", "t3")), any());
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
        when(toolRegistry.resolve(eq(List.of("t1")), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        verify(toolRegistry).resolve(eq(List.of("t1")), any());
    }

    // #region REF-1.3 生效面（两条主路径：摘除到达模型 / 描述改写到达模型）

    @Test
    @DisplayName("★ REF-1.3 生效面：被条件可用摘除的工具不进入 AgentLoopInput（模型真的看不到）")
    void shouldExcludeRemovedToolFromAgentLoopInput() {
        stubSimpleSkill();
        // registry 的「生效形态」已摘除 web_search（语义位在 registry 侧生效）
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of(
                new ToolDefinition("pullTasks", "拉取待处理收件箱"),
                new ToolDefinition("submitResult", "上交执行结果")));
        ToolCallback pull = callback("pullTasks", "拉取待处理收件箱");
        ToolCallback search = callback("web_search", "联网搜索");
        ToolCallback submit = callback("submitResult", "上交执行结果");
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{pull, search, submit});
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(captureLoopInput().enabledToolCallbacks())
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("pullTasks", "submitResult");
    }

    @Test
    @DisplayName("★ REF-1.3 生效面：registry 改写的描述进入模型可见 schema，name / inputSchema 逐字不变")
    void shouldApplyDynamicDescriptionToModelVisibleSchema() {
        stubSimpleSkill();
        when(toolRegistry.resolve(any(), any()))
                .thenReturn(List.of(new ToolDefinition("pullTasks", "本轮新描述")));
        ToolCallback pull = callback("pullTasks", "旧描述");
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{pull});
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        List<ToolCallback> visible = captureLoopInput().enabledToolCallbacks();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).getToolDefinition().description()).isEqualTo("本轮新描述");
        assertThat(visible.get(0).getToolDefinition().name()).isEqualTo("pullTasks");
        assertThat(visible.get(0).getToolDefinition().inputSchema()).isEqualTo(DEFAULT_INPUT_SCHEMA);
    }

    @Test
    @DisplayName("REF-1.3 零包装短路：生效描述与 provider 原描述相同 ⇒ 原回调实例直接透传")
    void shouldPassThroughOriginalCallbackWhenDescriptionUnchanged() {
        stubSimpleSkill();
        when(toolRegistry.resolve(any(), any()))
                .thenReturn(List.of(new ToolDefinition("pullTasks", "拉取待处理收件箱")));
        ToolCallback pull = callback("pullTasks", "拉取待处理收件箱");
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{pull});
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        assertThat(captureLoopInput().enabledToolCallbacks()).containsExactly(pull);
    }

    @Test
    @DisplayName("REF-1.3 事件可观测：TOOL_RESOLVED 新增 effectiveTools / removedTools，tools 原值不变")
    void shouldRecordEffectiveAndRemovedTools() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(
                        List.of("s1"), List.of("s1"), "", Map.of(), List.of("pullTasks", "web_search")));
        when(toolRegistry.resolve(any(), any()))
                .thenReturn(List.of(new ToolDefinition("pullTasks", "拉取待处理收件箱")));
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        org.mockito.ArgumentCaptor<Map<String, Object>> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(eventRecorder).record(eqRun("run-1-1"), eqLong(1L), eqLong(10L), eqInt(1), eqInt(6),
                eqType(AgentEventType.TOOL_RESOLVED), eqLong(3L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("tools", List.of("t1", "pullTasks", "web_search"))
                .containsEntry("effectiveTools", List.of("pullTasks"))
                .containsEntry("removedTools", List.of("t1", "web_search"));
    }

    @Test
    @DisplayName("REF-1.3 上下文透传：ToolContext 只由 AgentContext 已有事实装配（不许臆造字段）")
    void shouldAssembleToolContextFromAgentContext() {
        stubSimpleSkill();
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop("ok", null, 1, 0));

        new RuntimeTurnExecutor(
                agentLoop, toolExecutor, toolRegistry, agentSkillSpecService, sandboxProvider, toolCallbackProvider)
                .execute(context());

        ArgumentCaptor<ToolContext> captor = ArgumentCaptor.forClass(ToolContext.class);
        verify(toolRegistry).resolve(eq(List.of("t1")), captor.capture());
        ToolContext toolContext = captor.getValue();
        assertThat(toolContext.agentId()).isEqualTo(3L);
        assertThat(toolContext.taskId()).isEqualTo(1L);
        assertThat(toolContext.subTaskId()).isEqualTo(10L);
        assertThat(toolContext.turn()).isEqualTo(1);
        assertThat(toolContext.accessType()).isEqualTo(AgentAccessType.API_KEY_LLM);
        assertThat(toolContext.requiredSkills()).containsExactly("s1");
    }

    /** 技能解析桩：命中 s1、不声明额外工具（启用清单 = 上下文 tools 原样）。 */
    private void stubSimpleSkill() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of("s1"), List.of("s1"), ""));
    }

    private AgentLoopInput captureLoopInput() {
        ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
        verify(agentLoop).run(captor.capture());
        return captor.getValue();
    }

    private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    /**
     * 轻量真值替身（非 Mockito mock）：避免 strict-stubs 对「只读其中一个访问器」的用例
     * 报未用桩，同时让 {@code isSameAs} 的身份断言天然成立。
     */
    private record FakeDefinition(String name, String description, String inputSchema)
            implements org.springframework.ai.tool.definition.ToolDefinition {
    }

    private record FakeCallback(org.springframework.ai.tool.definition.ToolDefinition definition)
            implements ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
    }

    private static ToolCallback callback(String name, String description) {
        return new FakeCallback(new FakeDefinition(name, description, DEFAULT_INPUT_SCHEMA));
    }

    // #endregion

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
