package com.helloai.core.agent.runtime;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.sandbox.SandboxProvider;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * `MA-10` 改动 1：轮次耗尽 ⇒ 尽力交付（而非判失败）。
 *
 * <p>分两层测：<b>判据层</b>（{@link AgentLoopResult#isPartialDelivery()} 的真值表）与
 * <b>消费层</b>（{@link RuntimeTurnExecutor} 的状态映射 + 围栏闸豁免）。两层必须一起测——
 * 判据写对了但没人消费，等价于没改；消费改对了但判据把空正文也算交付，就是把失败伪装成交付。</p>
 *
 * <p><b>为什么改 {@code success} 的反证也在这里</b>：本类是「尽力交付」的唯一收口，
 * 若有实现图省事把 {@code AgentLoopResult.maxIterations} 的 {@code success} 改成 {@code true}，
 * 第①组用例会立刻失败（它断言 {@code success} 仍为 {@code false}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MA-10 改动 1：尽力交付")
class RuntimePartialDeliveryTest {

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

    /** ④ 出口分类要求的自证围栏（自然终态才需要；尽力交付刻意豁免）。 */
    private static final String FENCED = "\nVERIFICATION:\n- 已核对\nSTATUS: DONE\nNEXT_STEPS: 无";

    private RuntimeTurnExecutor executor() {
        return new RuntimeTurnExecutor(agentLoop, toolExecutor, toolRegistry,
                agentSkillSpecService, sandboxProvider, toolCallbackProvider);
    }

    private void stubLoop(AgentLoopResult result) {
        when(agentSkillSpecService.resolve(any()))
                .thenReturn(new AgentSkillSpecService.ResolvedSpec(List.of(), List.of(), ""));
        when(toolRegistry.resolve(any(), any())).thenReturn(List.of());
        when(agentLoop.run(any())).thenReturn(result);
    }

    private AgentContext context() {
        return AgentContext.builder()
                .runId("run-1-1").taskId(1L).subTaskId(10L).turn(1).step(0).agentId(3L)
                .accessType(AgentAccessType.API_KEY_LLM)
                .systemPrompt("sys").userPrompt("user")
                .chatModel(chatModel)
                .eventRecorder(eventRecorder)
                .build();
    }

    // #region ① 判据层：isPartialDelivery 真值表

    @Nested
    @DisplayName("① 判据层 AgentLoopResult#isPartialDelivery")
    class PartialDeliveryPredicate {

        @Test
        @DisplayName("★ 轮次耗尽 + 有正文 ⇒ true，但 success 仍为 false（真值不被掩盖）")
        void maxIterationsWithTextIsPartialDelivery() {
            AgentLoopResult result = AgentLoopResult.maxIterations("八成成品正文", 20, 15);

            assertThat(result.isPartialDelivery()).isTrue();
            // 关键反证：不能为了让消费侧判成功就把「循环层未自然终止」抹成 true
            assertThat(result.success()).isFalse();
            assertThat(result.finishReason()).isEqualTo("MAX_ITERATIONS");
        }

        @Test
        @DisplayName("★ 轮次耗尽 + 空正文 ⇒ false（空正文必须继续走既有失败护栏）")
        void maxIterationsWithoutTextIsNotPartialDelivery() {
            assertThat(AgentLoopResult.maxIterations("", 20, 15).isPartialDelivery()).isFalse();
        }

        @Test
        @DisplayName("★ 轮次耗尽 + 纯空白正文 ⇒ false（isBlank 口径，不留「全角空格算交付」的缝）")
        void maxIterationsWithBlankTextIsNotPartialDelivery() {
            assertThat(AgentLoopResult.maxIterations("  \n\t ", 20, 15).isPartialDelivery()).isFalse();
            assertThat(AgentLoopResult.maxIterations(null, 20, 15).isPartialDelivery()).isFalse();
        }

        @Test
        @DisplayName("自然 STOP 不属尽力交付（success=true 已由本判据外的主体表达）")
        void stopIsNotPartialDelivery() {
            assertThat(AgentLoopResult.stop("done", null, 1, 0).isPartialDelivery()).isFalse();
        }

        @Test
        @DisplayName("ERROR / MAX_TOKENS 不属尽力交付（不是「预算用尽」，是另一类终态）")
        void otherTerminalsAreNotPartialDelivery() {
            assertThat(AgentLoopResult.error("boom", 3, 2).isPartialDelivery()).isFalse();
            // MAX_TOKENS 带正文也不行：截断正文不是「已做到的八成」，而是「说到一半」
            assertThat(AgentLoopResult.maxTokens("说到一半", null, 3, 2).isPartialDelivery()).isFalse();
        }
    }

    // #endregion

    // #region ② 消费层：RuntimeTurnExecutor 状态映射

    @Nested
    @DisplayName("② 消费层 RuntimeTurnExecutor 状态映射")
    class ExecutorMapping {

        @Test
        @DisplayName("★ 轮次耗尽 + 有正文 ⇒ SUCCESS，finishReason 保留 MAX_ITERATIONS")
        void maxIterationsWithTextMapsToSuccess() {
            stubLoop(AgentLoopResult.maxIterations("八成成品正文", 20, 15, 1234));

            AgentExecutionResult result = executor().execute(context());

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(result.getOutput()).isEqualTo("八成成品正文");
            // 下游（评审 / MA-4 交付闸 / 运维）靠它区分「自然完成」与「预算用尽」
            assertThat(result.getFinishReason()).isEqualTo("MAX_ITERATIONS");
            assertThat(result.getTokenUsage()).isEqualTo(1234);
        }

        @Test
        @DisplayName("★ 尽力交付豁免 VERIFICATION 围栏：模型没有下一轮可以补围栏")
        void partialDeliveryIsExemptFromVerificationFence() {
            stubLoop(AgentLoopResult.maxIterations("八成成品正文（无围栏）", 20, 15));

            AgentExecutionResult result = executor().execute(context());

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(result.getOutput()).doesNotContain("missing_verification");
        }

        @Test
        @DisplayName("控制组：自然 STOP 缺围栏仍 FAILED（证明豁免只对尽力交付生效，不是把闸拆了）")
        void naturalStopStillRequiresFence() {
            stubLoop(AgentLoopResult.stop("done, no evidence", null, 1, 0));

            AgentExecutionResult result = executor().execute(context());

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(result.getOutput()).contains("agent_runtime_missing_verification");
        }

        @Test
        @DisplayName("★ 轮次耗尽 + 空正文 + 0 工具 ⇒ 仍 FAILED（空产出护栏不被改动 1 绕过）")
        void maxIterationsWithEmptyTextAndNoToolsStaysFailed() {
            stubLoop(AgentLoopResult.maxIterations("", 20, 0));

            AgentExecutionResult result = executor().execute(context());

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            // 不进「尽力交付」分支 ⇒ 不走空产出护栏，直接落失败分支透出 errorMessage
            // （与改造前逐字一致：改动前 success=false 时也是这条路径）
            assertThat(result.getOutput()).contains("max iterations");
            assertThat(result.getFinishReason()).isEqualTo("MAX_ITERATIONS");
        }

        @Test
        @DisplayName("★ 轮次耗尽 + 空正文 + 有工具 ⇒ 仍 FAILED（与自然 STOP 的不对称是刻意的）")
        void maxIterationsWithEmptyTextButToolsStaysFailed() {
            stubLoop(AgentLoopResult.maxIterations("", 20, 15));

            AgentExecutionResult result = executor().execute(context());

            // 不对称的依据：自然 STOP 时空正文 + 有工具 ⇒ SUCCESS（模型主动宣告结束，产物靠工具交付），
            // 见既有 `shouldKeepSuccessWhenEmptyTextButToolsInvoked`。但「轮次预算用尽」说明模型
            // **没做完**，此时空正文无法自证任何交付 ⇒ 必须判失败（蓝图 §4 `MA-10` ⑦ 第一条：
            // 「正文为空仍判 FAILED，否则把失败伪装成交付」）。改动 1 不放松这一条。
            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(result.getFinishReason()).isEqualTo("MAX_ITERATIONS");
        }

        @Test
        @DisplayName("ERROR 终态不受改动 1 影响：仍 FAILED 且输出错误原因")
        void errorTerminalStillFails() {
            stubLoop(AgentLoopResult.error("boom", 3, 2));

            AgentExecutionResult result = executor().execute(context());

            assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(result.getOutput()).isEqualTo("boom");
        }
    }

    // #endregion

    // #region ③ 改动 4：子任务链不再装总时长闸

    @Nested
    @DisplayName("③ 改动 4：子任务链只装单工具超时")
    class CancellationWiring {

        @Test
        @DisplayName("★ 子任务链装配的 LoopCancellation 只有工具超时，deadline token 恒为 NONE")
        void subtaskChainCarriesToolTimeoutOnly() {
            stubLoop(AgentLoopResult.stop("ok" + FENCED, null, 1, 0));
            RuntimeTurnExecutor executor = executor();
            // 故意把总时长配成极小值：若它仍被装配，下面断言会失败（本用例即防线）
            AgentExecutionProperties props = new AgentExecutionProperties();
            props.setTurnDeadlineSeconds(1L);
            props.setToolCallTimeoutMillis(300000L);
            ReflectionTestUtils.setField(executor, "executionProperties", props);

            executor.execute(context());

            ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
            verify(agentLoop).run(captor.capture());
            var cancellation = captor.getValue().cancellation();
            assertThat(cancellation.toolTimeoutMillis()).isEqualTo(300000L);
            // token 为共享 NONE 常量 ⇒ 不装截止时间装饰器（改造 4 的核心断言）
            assertThat(cancellation.token())
                    .isSameAs(com.helloai.core.agent.runtime.cancel.CancellationToken.NONE);
        }

        @Test
        @DisplayName("配置 Bean 缺失 ⇒ LoopCancellation.none()（行为与改造前一致）")
        void missingPropertiesYieldsNone() {
            stubLoop(AgentLoopResult.stop("ok" + FENCED, null, 1, 0));

            executor().execute(context());

            ArgumentCaptor<AgentLoopInput> captor = ArgumentCaptor.forClass(AgentLoopInput.class);
            verify(agentLoop).run(captor.capture());
            var cancellation = captor.getValue().cancellation();
            assertThat(cancellation.toolTimeoutMillis()).isZero();
            // 共享 NONE 实例的身份比较是「零包装」的判据（见 LoopCancellation javadoc）
            assertThat(cancellation).isSameAs(com.helloai.core.agent.runtime.cancel.LoopCancellation.none());
        }
    }

    // #endregion
}
