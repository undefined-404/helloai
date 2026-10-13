package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.runtime.loop.AgentLoop;
import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.tool.ToolExecutor;
import com.helloai.core.shared.util.VerificationFence;
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

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * REF-8.4 ② 收尾轮（内部 LLM 自我审计）：{@link FinalReportFenceHook} 经 {@link ChatModelToolLoop}
 * 终止分发的有界无工具重跑。
 *
 * <p>语义：内部 LLM 提交前先自审（VERIFICATION/STATUS/NEXT_STEPS）；缺/不合格 ⇒ 恰好一次收尾轮
 * 自纠；外部 Agent 不参与；二次仍不合格不循环（交出口分类 fail-close）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("REF-8.4 ② 收尾轮 FinalReportFenceHook")
class FinalReportFenceHookTest {

    @Mock
    private ToolExecutor toolExecutor;

    private final AgentLoop loop =
            new ChatModelToolLoop(ContextCompactor.noop(), List.of(new FinalReportFenceHook()));

    @Test
    @DisplayName("内部 LLM 缺围栏 ⇒ 恰好一次无工具收尾轮，结果含围栏，iterations+1")
    void internalMissingFence_runsOnceFinalizeAndSucceeds() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("已完成步骤，但没写围栏"))
                .enqueue(text(withFence("已完成步骤")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();
        assertThat(result.iterations()).isEqualTo(2);     // 草稿 1 + 收尾 1
        assertThat(model.callCount()).isEqualTo(2);       // 有界恒一次
        verifyNoInteractions(toolExecutor);               // 收尾轮不重入工具
    }

    @Test
    @DisplayName("内部 LLM 围栏已合格 ⇒ 零额外调用（自审已完成）")
    void internalAdequateFence_zeroExtraCall() {
        StubChatModel model = new StubChatModel().enqueue(text(withFence("done")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(1);        // 无收尾轮
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("非内部（外部 Agent）缺围栏 ⇒ 不触发收尾轮（其自审走自身工作空间 + reviewer）")
    void externalMissingFence_notGated() {
        StubChatModel model = new StubChatModel().enqueue(text("外部 agent 的自由报告，无围栏"));

        AgentLoopResult result = loop.run(input(model, null));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(1);        // 无收尾轮
        assertThat(result.text()).isEqualTo("外部 agent 的自由报告，无围栏");
    }

    @Test
    @DisplayName("收尾轮重跑抛异常 ⇒ 沿用草稿（交出口分类 fail-close，不阻断）")
    void finalizeThrows_keepsDraft() {
        StubChatModel model = new StubChatModel();
        model.enqueue(text("无围栏草稿"));
        model.throwOnCall(2, new RuntimeException("finalize boom"));   // 第 1 次草稿，第 2 次收尾抛

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("无围栏草稿");       // 沿用草稿
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("收尾轮后仍缺围栏 ⇒ 返回修订结果但不循环（有界恒一次）")
    void stillMissingAfterFinalize_doesNotLoop() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("第一次没围栏"))
                .enqueue(text("第二次还是没围栏"));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(model.callCount()).isEqualTo(2);        // 有界：草稿 + 恰一次收尾，不第三次
        assertThat(result.iterations()).isEqualTo(2);
    }

    @Test
    @DisplayName("② 收尾轮空产出 ⇒ 沿用草稿（防止交付物被清空）")
    void finalizeEmptyOutput_keepsDraft() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("真实交付正文，无围栏"))
                .enqueue(text(""));   // 收尾轮返回空正文（推理模型只回 reasoning，无 getResults）

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("真实交付正文，无围栏");   // 沿用草稿，未被清空
        assertThat(result.iterations()).isEqualTo(1);
    }

    @Test
    @DisplayName("③ 收尾轮 prompt 含【本轮任务要求】+ 系统提示（自审依据不丢，堵假绿）")
    void finalizePromptIncludesTaskRequirement() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿"))
                .enqueue(text(withFence("done")));

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("VERIFICATION:");
        // 断言第 2 次（收尾）调用的 prompt 携带任务要求与系统提示 —— 自审依据确实在 prompt 里
        List<Message> lastPrompt = model.lastPrompt();
        String joined = lastPrompt.stream()
                .map(Message::getText).filter(Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("【本轮任务要求】");
        assertThat(joined).contains("user");    // 原 userPrompt 剥出仅系统提示时系统内容是"sys"
    }

    @Test
    @DisplayName("③a 收尾轮：收尾那一轮的 token 计入累计")
    void finalizeAccumulatesTokens() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿"))                       // 收尾前无 token
                .enqueue(textWithUsage(withFence("done"), 15));   // 收尾轮 15 token

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM));

        assertThat(result.success()).isTrue();
        assertThat(VerificationFence.parse(result.text()).adequate()).isTrue();
        assertThat(result.tokenUsage()).isEqualTo(15);            // 收尾轮 token 已计入
    }

    @Test
    @DisplayName("③c 收尾轮：iterations 与 maxIterations 取 min，不超配置上限")
    void finalizeIterationsCappedByMaxIterations() {
        StubChatModel model = new StubChatModel()
                .enqueue(text("无围栏草稿"))                       // 草稿（iterations=1）
                .enqueue(text(withFence("done")));                 // 收尾轮

        AgentLoopResult result = loop.run(input(model, AgentAccessType.API_KEY_LLM, 1));

        assertThat(result.success()).isTrue();
        // maxIterations=1 ⇒ 收尾轮计 iterations 被截断到 1，而不是 2（2 = 草稿 1 + 收尾 1）
        assertThat(result.iterations()).isEqualTo(1);
    }

    private static String withFence(String body) {
        return body + "\nVERIFICATION:\n- 已验证\nSTATUS: DONE\nNEXT_STEPS: 无";
    }

    private AgentLoopInput input(StubChatModel model, AgentAccessType accessType) {
        return input(model, accessType, AgentLoopInput.DEFAULT_MAX_ITERATIONS);
    }

    private AgentLoopInput input(StubChatModel model, AgentAccessType accessType, int maxIterations) {
        return new AgentLoopInput(model, "sys", "user", toolExecutor, List.of(),
                maxIterations, "run-1-1", 1L, 10L, 1, 3L, null, null, accessType);
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(content).build())), null);
    }

    private static ChatResponse textWithUsage(String content, int totalTokens) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(content).build())),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(1, 1, totalTokens))
                        .build());
    }

    /** 确定性 ChatModel stub：可编程响应队列 + 调用计数 + 失败注入。 */
    private static final class StubChatModel implements ChatModel {
        private final Queue<ChatResponse> responses = new LinkedList<>();
        private final java.util.Map<Integer, RuntimeException> throwPlan = new java.util.HashMap<>();
        private int calls = 0;
        private List<Message> lastPrompt = java.util.Collections.emptyList();

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        /** 指定第 N 次调用抛异常（N 从 1 起）；用于模拟「收尾轮重跑失败」。 */
        StubChatModel throwOnCall(int n, RuntimeException ex) {
            throwPlan.put(n, ex);
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            lastPrompt = prompt.getInstructions();
            RuntimeException ex = throwPlan.remove(calls);
            if (ex != null) {
                throw ex;
            }
            return responses.poll();
        }

        int callCount() {
            return calls;
        }

        /** 最近一次调用的 prompt 指令（收尾 prompt 自审依据断言用）。 */
        List<Message> lastPrompt() {
            return lastPrompt;
        }
    }
}
