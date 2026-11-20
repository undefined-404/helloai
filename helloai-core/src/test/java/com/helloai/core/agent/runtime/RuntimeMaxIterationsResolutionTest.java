package com.helloai.core.agent.runtime;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `MA-10` 改动 2：轮次预算解析（分档默认 + 子任务级 `maxIterationsHint` 覆盖 + 钳制）。
 *
 * <p><b>为什么必须测「未给 hint 时行为逐字不变」</b>：改动 2 的第三条要求就是「未给 hint ⇒
 * 回落档位默认（行为与改造前一致）」。这条如果没人测，将来任何一次重构把分支顺序改反，
 * 都会让存量任务静默拿到不同的轮次预算，而现象只在「某个长任务突然收尾」时出现。</p>
 *
 * <p><b>为什么必须测钳制</b>：hint 来自拆解阶段的模型输出，完全可能给 500。不钳的话
 * 单任务成本可被一次拆解放大一个量级，而这类缺陷不会在任何一条日志里露头。</p>
 *
 * <p>executionProperties 是 field 注入（非构造器），故用 {@link ReflectionTestUtils} 显式装配——
 * 与既有单测「显式构造 6 参 RuntimeTurnExecutor」的约定一致（不动构造器签名）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MA-10 改动 2：轮次预算解析")
class RuntimeMaxIterationsResolutionTest {

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

    /**
     * 装配执行器（可选注入配置 Bean）。
     *
     * <p>不注入配置 = 模拟「单测显式构造」场景，验证配置缺失时的回落仍确定。</p>
     */
    private RuntimeTurnExecutor executor(AgentExecutionProperties props) {
        RuntimeTurnExecutor executor = new RuntimeTurnExecutor(agentLoop, toolExecutor, toolRegistry,
                agentSkillSpecService, sandboxProvider, toolCallbackProvider);
        if (props != null) {
            ReflectionTestUtils.setField(executor, "executionProperties", props);
        }
        return executor;
    }

    /** 桩：技能空解析、无工具，Loop 返回带围栏的自然 STOP（避免撞 ④ 出口分类闸）。 */
    private void stubHappyLoop() {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(AgentLoopResult.stop(
                "done\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无", null, 1, 0));
    }

    private AgentContext context(AgentAccessType accessType, Integer maxIterationsHint) {
        return AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .accessType(accessType)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .eventRecorder(eventRecorder)
                .maxIterationsHint(maxIterationsHint)
                .build();
    }

    private int captureResolvedMaxIterations() {
        ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
        verify(agentLoop).run(captor.capture());
        return captor.getValue().maxIterations();
    }

    // #region 未给 hint ⇒ 回落分档默认（行为与改造前一致）

    @Test
    @DisplayName("未给 hint + 内部 LLM ⇒ 内部分档（配置 20）")
    void shouldFallBackToInternalTierWhenHintAbsent() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setInternalMaxIterations(20);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, null));

        assertThat(captureResolvedMaxIterations()).isEqualTo(20);
    }

    @Test
    @DisplayName("未给 hint + 外部 Agent ⇒ 外部分档（配置 5）")
    void shouldFallBackToExternalTierWhenHintAbsent() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setExternalMaxIterations(5);
        executor(props).execute(context(AgentAccessType.CLI_CLIENT, null));

        assertThat(captureResolvedMaxIterations()).isEqualTo(5);
    }

    @Test
    @DisplayName("未给 hint + 配置 Bean 缺失 ⇒ 回落硬默认（内部 10 / 外部 5）")
    void shouldFallBackToHardDefaultWhenPropertiesAbsent() {
        stubHappyLoop();
        executor(null).execute(context(AgentAccessType.API_KEY_LLM, null));

        assertThat(captureResolvedMaxIterations())
                .isEqualTo(AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA);
    }

    @Test
    @DisplayName("档位配置 <=0 ⇒ 回落硬默认（配置写错不该让执行链失效）")
    void shouldFallBackWhenTierConfigNonPositive() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setInternalMaxIterations(0);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, null));

        assertThat(captureResolvedMaxIterations())
                .isEqualTo(AgentLoopInput.DEFAULT_MAX_ITERATIONS_EXTRA);
    }

    // #endregion

    // #region hint 覆盖

    @Test
    @DisplayName("★ hint 覆盖档位默认：内部 LLM 给 hint=30 ⇒ 用 30（不是 20）")
    void shouldPreferHintOverTierDefault() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setInternalMaxIterations(20);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, 30));

        assertThat(captureResolvedMaxIterations()).isEqualTo(30);
    }

    @Test
    @DisplayName("hint 低于档位默认是合法意图（给 3 ⇒ 用 3，不强行抬到档位值）")
    void shouldHonorHintBelowTierDefault() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setInternalMaxIterations(20);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, 3));

        assertThat(captureResolvedMaxIterations()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ hint 超上限被钳制：给 500、上限 50 ⇒ 50（防一次拆解放大成本一个量级）")
    void shouldClampHintToCeiling() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setMaxIterationsHintCeiling(50);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, 500));

        assertThat(captureResolvedMaxIterations()).isEqualTo(50);
    }

    @Test
    @DisplayName("★ hint 低于结构性下限被抬高：给 1 ⇒ 2（maxIterations<=1 会让护栏从第 1 轮就禁用工具）")
    void shouldRaiseHintToStructuralFloor() {
        stubHappyLoop();
        executor(null).execute(context(AgentAccessType.API_KEY_LLM, 1));

        assertThat(captureResolvedMaxIterations()).isEqualTo(2);
    }

    @Test
    @DisplayName("上限配置低于下限（写错）⇒ 仍钳到下限，不产生非法区间")
    void shouldSurviveCeilingBelowFloor() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setMaxIterationsHintCeiling(1);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, 5));

        assertThat(captureResolvedMaxIterations()).isEqualTo(2);
    }

    // #endregion

    // #region 非法 hint ⇒ 按「未给」处理

    @Test
    @DisplayName("hint 为 0 ⇒ 视为未给，回落档位默认（0 与「没写」在可观测面必须分清）")
    void shouldTreatZeroHintAsAbsent() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setInternalMaxIterations(20);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, 0));

        assertThat(captureResolvedMaxIterations()).isEqualTo(20);
    }

    @Test
    @DisplayName("hint 为负数 ⇒ 视为未给，回落档位默认")
    void shouldTreatNegativeHintAsAbsent() {
        stubHappyLoop();
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setInternalMaxIterations(20);
        executor(props).execute(context(AgentAccessType.API_KEY_LLM, -7));

        assertThat(captureResolvedMaxIterations()).isEqualTo(20);
    }

    // #endregion
}
