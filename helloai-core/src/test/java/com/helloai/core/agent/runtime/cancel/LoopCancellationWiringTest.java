package com.helloai.core.agent.runtime.cancel;

import com.helloai.core.agent.runtime.loop.AgentLoopInput;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.agent.runtime.loop.impl.ChatModelToolLoop;
import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * REF-8.6 接线与「取消中断」专项测试。
 *
 * <p>覆盖两件事：① {@link AgentLoopInput} 的紧凑构造器是取消装配的<b>唯一咽喉</b>
 * （不限时不包装、启用时必包装——防「规则存在 ≠ 全路径强制」）；
 * ② <b>取消真能中断循环</b>：工具调用中途取消 ⇒ 循环<b>不再发起下一轮模型调用</b>,
 * 异常上抛给调用方（这是本批要求「专测取消中断用例」的落点）。</p>
 */
@DisplayName("REF-8.6 取消接线与中断")
class LoopCancellationWiringTest {

    @Test
    @DisplayName("不限取消 ⇒ 零包装（模型 / 工具执行器身份不变，行为与改造前逐字一致）")
    void noWrappingWhenCancellationInactive() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutor executor = (name, args) -> ToolExecutionResult.success(name, "ok");

        AgentLoopInput viaNull = new AgentLoopInput(model, "s", "u", executor, List.of(),
                5, "run-1-1", 1L, 2L, 1, 3L, null, null, null, null);
        AgentLoopInput viaNone = new AgentLoopInput(model, "s", "u", executor, List.of(),
                5, "run-1-1", 1L, 2L, 1, 3L, null, null, null, LoopCancellation.of(0, 0));

        assertThat(viaNull.chatModel()).isSameAs(model);
        assertThat(viaNull.toolExecutor()).isSameAs(executor);
        assertThat(viaNone.chatModel()).isSameAs(model);
        assertThat(viaNone.toolExecutor()).isSameAs(executor);
    }

    @Test
    @DisplayName("启用取消 / 超时 ⇒ 紧凑构造器强制包装（任何调用方都绕不过）")
    void wrapsWhenCancellationActive() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutor executor = (name, args) -> ToolExecutionResult.success(name, "ok");

        AgentLoopInput input = new AgentLoopInput(model, "s", "u", executor, List.of(),
                5, "run-1-1", 1L, 2L, 1, 3L, null, null, null, LoopCancellation.of(60, 1000));

        assertThat(input.chatModel()).isInstanceOf(CancellableChatModel.class);
        assertThat(input.toolExecutor()).isInstanceOf(CancellableToolExecutor.class);
    }

    @Test
    @DisplayName("★取消中断：工具调用中途取消 ⇒ 循环不发起下一轮模型调用，异常上抛")
    void cancellationInterruptsLoopBeforeNextModelCall() {
        CancellationToken.Mutable token = CancellationToken.mutable();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();

        // 第 1 轮：模型要调工具；工具执行时请求取消；此后循环不应再调模型
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "internal_list_skills", "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(message)), null);
        };
        ToolExecutor toolExecutor = (name, args) -> {
            toolCalls.incrementAndGet();
            token.cancel("工具执行中发现任务已取消");
            return ToolExecutionResult.success(name, "[]");
        };

        AgentLoopInput input = new AgentLoopInput(model, "sys", "user", toolExecutor, List.of(),
                5, "run-1-1", 1L, 2L, 1, 3L, null, null, null,
                new LoopCancellation(token, 0L));

        assertThatThrownBy(() -> new ChatModelToolLoop().run(input))
                .isInstanceOf(AgentCancelledException.class)
                .hasMessage("工具执行中发现任务已取消");

        assertThat(modelCalls.get()).isEqualTo(1);   // 第 2 轮未发起 ⇒ 取消真的中断了循环
        assertThat(toolCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("截止时间到期 ⇒ 下一轮模型调用前即中断（与外部取消同一条路径）")
    void deadlineInterruptsLoop() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            modelCalls.incrementAndGet();
            try {
                Thread.sleep(1200L);   // 首轮就超过 1s 截止
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "t", "{}")))
                    .build();
            return new ChatResponse(List.of(new Generation(message)), null);
        };
        ToolExecutor toolExecutor = (name, args) -> ToolExecutionResult.success(name, "ok");

        AgentLoopInput input = new AgentLoopInput(model, "sys", "user", toolExecutor, List.of(),
                5, "run-1-1", 1L, 2L, 1, 3L, null, null, null,
                LoopCancellation.of(1, 0));

        assertThatThrownBy(() -> new ChatModelToolLoop().run(input))
                .isInstanceOf(AgentCancelledException.class)
                .hasMessageContaining("turn deadline exceeded");
        // 首轮模型调用在途结果被丢弃（调用**后**检查），循环未进入第 2 轮
        assertThat(modelCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("未取消时循环正常终态（包装器透明，不引入行为变化）")
    void loopStillWorksNormallyWhenWrapped() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("final").build())), null);
        ToolExecutor toolExecutor = (name, args) -> ToolExecutionResult.success(name, "ok");

        AgentLoopInput input = new AgentLoopInput(model, "sys", "user", toolExecutor, List.of(),
                5, "run-1-1", 1L, 2L, 1, 3L, null, null, null, LoopCancellation.of(60, 1000));

        AgentLoopResult result = new ChatModelToolLoop().run(input);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("final");
        assertThat(result.finishReason()).isEqualTo("STOP");
    }
}
