package com.helloai.core.agent.runtime;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.runtime.cancel.CancellableChatModel;
import com.helloai.core.agent.runtime.cancel.CancellableToolExecutor;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolDefinition;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolInvocationContext;
import com.helloai.core.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BusinessSurfaceLoopRunner} 单元测试（REF-8.5 覆盖面收敛）。
 *
 * <p>覆盖：白名单判定（命中 / 未命中 / 开关关闭 / 接入类型不符）、循环装配
 * （模型 / 工具并集 / 生效形态过滤 / 带外标识注入与解绑）、结果映射
 * （STOP / MAX_ITERATIONS / ERROR / 异常四态）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessSurfaceLoopRunner（REF-8.5 业务面循环入口）")
class BusinessSurfaceLoopRunnerTest {

    private static final Long AGENT_ID = 101L;
    private static final Long TASK_ID = 9001L;
    private static final Long SUB_TASK_ID = 2001L;

    @Mock
    private AgentChatClientService agentChatClientService;

    @Mock
    private AgentLlmCredentialResolver agentLlmCredentialResolver;

    @Mock
    private AgentMcpServerService agentMcpServerService;

    @Mock
    private AgentSkillSpecService agentSkillSpecService;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ToolExecutor toolExecutor;

    @Mock
    private AgentLoop agentLoop;

    @Mock
    private ToolCallbackProvider toolCallbackProvider;

    private AgentExecutionProperties properties;
    private BusinessSurfaceLoopRunner runner;

    @BeforeEach
    void setUp() {
        properties = new AgentExecutionProperties();
        // mock 模式：凭据解析短路（Vault 路径由 AgentChatClientServiceImpl 侧承担，这里不重复覆盖）
        properties.setMockMode(true);
        runner = new BusinessSurfaceLoopRunner(
                agentChatClientService, agentLlmCredentialResolver, agentMcpServerService,
                agentSkillSpecService, properties, toolRegistry, toolExecutor, agentLoop,
                toolCallbackProvider);
    }

    // ────────────── 白名单判定 ──────────────

    @Test
    @DisplayName("scene 命中白名单 + API_KEY_LLM + 开关开启 ⇒ 接管")
    void shouldSupportWhitelistedScene() {
        for (String scene : List.of("planner_decompose", "subtask_review",
                "task_final_report", "task_final_report_review")) {
            assertThat(runner.supports(apiKeyAgent(), taskWithScene(scene, TASK_ID, SUB_TASK_ID)))
                    .as("scene=%s 应为进循环面", scene)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("scene 不在白名单（如流式澄清面）⇒ 不接管，回落单次")
    void shouldNotSupportNonWhitelistedScene() {
        assertThat(runner.supports(apiKeyAgent(), taskWithScene("requirement_clarify", TASK_ID, null)))
                .isFalse();
        assertThat(runner.supports(apiKeyAgent(), taskWithScene("task_final_report_outline", TASK_ID, null)))
                .isFalse();
    }

    @Test
    @DisplayName("scene 缺失 ⇒ 不接管（无身份的任务不猜场景）")
    void shouldNotSupportWhenSceneAbsent() {
        AgentTask task = AgentTask.builder().systemPrompt("s").userPrompt("u").build();
        assertThat(runner.supports(apiKeyAgent(), task)).isFalse();
    }

    @Test
    @DisplayName("开关关闭 ⇒ 全部面回落单次（即时回退通道）")
    void shouldNotSupportWhenSwitchOff() {
        properties.setBusinessLoopEnabled(false);
        assertThat(runner.supports(apiKeyAgent(), taskWithScene("planner_decompose", TASK_ID, null)))
                .isFalse();
    }

    @Test
    @DisplayName("接入类型非 API_KEY_LLM ⇒ 不接管（外部 Agent 走原有链）")
    void shouldNotSupportNonApiKeyLlmAgent() {
        Agent agent = apiKeyAgent();
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        assertThat(runner.supports(agent, taskWithScene("planner_decompose", TASK_ID, null))).isFalse();
    }

    // ────────────── 循环装配与结果映射 ──────────────

    @Test
    @DisplayName("STOP：装配循环输入（模型/工具/标识）并映射为成功结果")
    void shouldRunLoopAndMapStopResult() {
        ToolCallback skillTool = callback("internal_list_skills", "技能目录");
        ToolCallback detailTool = callback("internal_get_sub_task_detail", "子任务详情");
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{skillTool, detailTool});
        // 生效形态只留 list_skills ⇒ 第二个工具必须不出现在模型可见列表（可见面单口径判据）
        when(toolRegistry.resolve(anyList(), any()))
                .thenReturn(List.of(new ToolDefinition("internal_list_skills", "技能目录")));
        when(agentMcpServerService.getEnabledToolsForAccess(anyLong(), any()))
                .thenReturn(List.of("internal_list_skills", "internal_get_sub_task_detail"));
        when(agentSkillSpecService.resolve(anyList()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        ChatModel chatModel = mock(ChatModel.class);
        when(agentChatClientService.buildChatModel(any(), any(), any())).thenReturn(chatModel);
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            capturedInput.set(invocation.getArgument(0));
            return AgentLoopResult.stop("核验结论", "思考", 2, 1, 42);
        });

        AgentResult result = runner.runInLoop(apiKeyAgent(), taskWithScene("subtask_review", TASK_ID, SUB_TASK_ID));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("核验结论");
        assertThat(result.getThinking()).isEqualTo("思考");
        assertThat(result.getFinishReason()).isEqualTo("STOP");
        assertThat(result.getTokenUsage()).isEqualTo(42);
        assertThat(result.getExecutorName()).isEqualTo("BusinessSurfaceLoopRunner");

        AgentLoopInput input = capturedInput.get();
        assertThat(input).isNotNull();
        // REF-8.6：默认配置（截止 1800s / 单工具 300s）下，唯一咽喉把模型与工具执行器
        // 包上取消装饰器 ⇒ 这里断言的是「已装配」而非原始身份（原始身份由
        // LoopCancellationWiringTest#noWrappingWhenCancellationInactive 单独覆盖）。
        assertThat(input.chatModel()).isInstanceOf(CancellableChatModel.class);
        assertThat(input.toolExecutor()).isInstanceOf(CancellableToolExecutor.class);
        assertThat(input.agentId()).isEqualTo(AGENT_ID);
        assertThat(input.taskId()).isEqualTo(TASK_ID);
        assertThat(input.subTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(input.turn()).isEqualTo(1);
        assertThat(input.accessType()).isEqualTo(AgentAccessType.API_KEY_LLM);
        assertThat(input.runId()).isEqualTo("run-" + TASK_ID + "-1");
        // 事件 / 检查点刻意不接（业务面不经 agent_event）
        assertThat(input.eventRecorder()).isNull();
        assertThat(input.loopCheckpointListener()).isNull();
        // 可见工具 = resolve 生效形态（第二个工具被摘除）
        assertThat(input.enabledToolCallbacks()).containsExactly(skillTool);
    }

    @Test
    @DisplayName("循环内绑定执行者标识（agentId/subTaskId），返回后解绑")
    void shouldBindAndUnbindInvocationContextAroundLoop() {
        stubLoopDependencies();
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            capturedInput.set(invocation.getArgument(0));
            boundDuringRun.set(ToolInvocationContext.current());
            return AgentLoopResult.stop("ok", null, 1, 0, 5);
        });

        runner.runInLoop(apiKeyAgent(), taskWithScene("planner_decompose", TASK_ID, null));

        ToolInvocationContext.Ids bound = boundDuringRun.get();
        assertThat(bound).isNotNull();
        assertThat(bound.agentId()).isEqualTo(AGENT_ID);
        assertThat(bound.subTaskId()).isNull();
        assertThat(ToolInvocationContext.current()).isNull();
    }

    @Test
    @DisplayName("MAX_ITERATIONS：携末轮正文判成功，finishReason 原样透出（真值不掩盖）")
    void shouldMapMaxIterationsResult() {
        stubLoopDependencies();
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.maxIterations("半成品草案", 10, 3, 500));

        AgentResult result = runner.runInLoop(apiKeyAgent(), taskWithScene("task_final_report", TASK_ID, null));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("半成品草案");
        assertThat(result.getFinishReason()).isEqualTo("MAX_ITERATIONS");
    }

    @Test
    @DisplayName("ERROR 终态 ⇒ 返回 null，由调用方回落单次链路")
    void shouldFallbackOnErrorResult() {
        stubLoopDependencies();
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.error("模型异常", 1, 0));

        assertThat(runner.runInLoop(apiKeyAgent(), taskWithScene("planner_decompose", TASK_ID, null)))
                .isNull();
    }

    @Test
    @DisplayName("循环抛异常 ⇒ 返回 null（不使业务面比改造前更差）")
    void shouldFallbackOnException() {
        stubLoopDependencies();
        when(agentLoop.run(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(runner.runInLoop(apiKeyAgent(), taskWithScene("planner_decompose", TASK_ID, null)))
                .isNull();
        // 异常路径也必须解绑（防线程复用串味）
        assertThat(ToolInvocationContext.current()).isNull();
    }

    @Test
    @DisplayName("工具集为空时仍可装配（无工具循环 = 单轮终态，不阻断）")
    void shouldRunWithoutTools() {
        stubLoopDependencies();
        stubLoopCapturingStop();

        AgentResult result = runner.runInLoop(apiKeyAgent(), taskWithScene("subtask_review", TASK_ID, SUB_TASK_ID));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(capturedInput.get().enabledToolCallbacks()).isEmpty();
        verifyNoInteractions(toolExecutor);
    }

    // ────────────── 夹具 ──────────────

    /** 捕获送进循环的输入（断言装配事实）。 */
    private final AtomicReference<AgentLoopInput> capturedInput = new AtomicReference<>();
    /** 捕获循环执行期间线程局部的执行者标识（断言绑定事实）。 */
    private final AtomicReference<ToolInvocationContext.Ids> boundDuringRun = new AtomicReference<>();

    /**
     * 装配侧最小桩（模型 / 工具 / 技能三段），<b>不含</b> {@code agentLoop.run}
     * ——循环返回由各用例自持（避免同方法重复 stub 触发严格桩检查）。
     */
    private void stubLoopDependencies() {
        when(agentMcpServerService.getEnabledToolsForAccess(anyLong(), any())).thenReturn(List.of());
        when(agentSkillSpecService.resolve(anyList()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(anyList(), any())).thenReturn(List.of());
        when(toolCallbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        when(agentChatClientService.buildChatModel(any(), any(), any())).thenReturn(mock(ChatModel.class));
    }

    /** 捕获输入的单轮成功循环（配合 {@link #stubLoopDependencies()} 使用）。 */
    private void stubLoopCapturingStop() {
        when(agentLoop.run(any())).thenAnswer(invocation -> {
            capturedInput.set(invocation.getArgument(0));
            return AgentLoopResult.stop("ok", null, 1, 0, 1);
        });
    }

    private static Agent apiKeyAgent() {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setName("planner-agent");
        agent.setRole(AgentRole.PLANNER);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setModelType("mock:helloai-mock-executor");
        return agent;
    }

    private static AgentTask taskWithScene(String scene, Long taskId, Long subTaskId) {
        Map<String, Object> context = subTaskId != null
                ? Map.of("scene", scene, "taskId", taskId, "subTaskId", subTaskId)
                : Map.of("scene", scene, "taskId", taskId);
        return AgentTask.builder()
                .subTaskId(subTaskId)
                .systemPrompt("sys")
                .userPrompt("user")
                .context(context)
                .build();
    }

    /** 造一个带 spring-ai 工具定义的 ToolCallback（只用于可见面断言）。 */
    private static ToolCallback callback(String name, String description) {
        org.springframework.ai.tool.definition.ToolDefinition definition =
                mock(org.springframework.ai.tool.definition.ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        // 被名字过滤掉的工具不会读 description（可见面过滤先看名字）⇒ 宽松桩，避免严格桩误报
        lenient().when(definition.description()).thenReturn(description);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }
}
