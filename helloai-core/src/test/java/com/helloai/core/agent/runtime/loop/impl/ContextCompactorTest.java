package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.common.config.AgentExecutionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextCompactor} 单测（REF-8.2.3，对齐 DSH {@code compaction-basic}）。
 *
 * <p>覆盖：开关/阈值短路 · 剪枝足够则不摘要 · 仍超预算走 LLM 摘要（替换而非追加、
 * 逐字保留首尾、不拆 assistant+tool 对）· 摘要失败不阻断 · 溢出强制路径。</p>
 */
@DisplayName("ContextCompactor 上下文压缩（REF-8.2.3）")
class ContextCompactorTest {

    private static AgentExecutionProperties props() {
        AgentExecutionProperties p = new AgentExecutionProperties();
        p.setContextCompactionEnabled(true);
        p.setContextWindowTokens(200);
        p.setContextCompactionThresholdRatio(0.8);   // 预算 = 160 token
        p.setContextCompactionRetainRatio(0.16);     // 尾部逐字保留 ≈ 32 token
        p.setToolResultPruneThresholdChars(100);
        p.setToolResultPruneHeadChars(20);
        p.setToolResultPruneTailChars(10);
        return p;
    }

    @Test
    @DisplayName("关闭压缩 → 恒返回 null（行为与改造前一致）")
    void shouldNoopWhenDisabled() {
        AgentExecutionProperties p = props();
        p.setContextCompactionEnabled(false);
        List<Message> messages = new ArrayList<>(List.of(new UserMessage(variedText(2000, "x"))));

        assertThat(new ContextCompactor(p).compactIfNeeded(messages, new StubChatModel(), null)).isNull();
        assertThat(messages).hasSize(1);
    }

    @Test
    @DisplayName("未达压力阈值 → 不动消息、不调模型")
    void shouldSkipUnderBudget() {
        StubChatModel model = new StubChatModel();
        List<Message> messages = new ArrayList<>(List.of(new UserMessage("短任务")));

        assertThat(new ContextCompactor(props()).compactIfNeeded(messages, model, null)).isNull();
        assertThat(model.calls).isZero();
    }

    @Test
    @DisplayName("工具结果剪枝已足够 → 只剪枝、不摘要（省一次 LLM 调用）")
    void shouldPruneWithoutSummarizing() {
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage("sys"),
                new UserMessage("任务"),
                new AssistantMessage(""),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "internal_read_attachment", variedText(500, "tok")))).build()));
        StubChatModel model = new StubChatModel();

        CompactionOutcome outcome = new ContextCompactor(props()).compactIfNeeded(messages, model, null);

        assertThat(outcome).isNotNull();
        assertThat(outcome.prunedToolResults()).isEqualTo(1);
        assertThat(outcome.summarized()).isFalse();
        assertThat(outcome.estimatedTokensBefore()).isGreaterThan(outcome.estimatedTokensAfter());
        assertThat(model.calls).isZero();   // 剪枝足够 ⇒ 未调摘要
    }

    @Test
    @DisplayName("剪枝不够（无工具结果、纯长历史）→ LLM 摘要：替换而非追加，首尾逐字保留")
    void shouldSummarizeWhenStillOverBudget() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(new UserMessage("首条任务上下文（必须保留）"));
        for (int i = 0; i < 5; i++) {
            messages.add(new AssistantMessage("第 " + i + " 轮正文 " + variedText(200, "a")));
        }
        Message lastTail = new AssistantMessage("最近一轮（必须逐字保留） " + variedText(200, "t"));
        messages.add(lastTail);
        int before = messages.size();
        StubChatModel model = new StubChatModel().enqueue(textResponse("## 已完成\n- 前面五轮的要点"));

        CompactionOutcome outcome = new ContextCompactor(props()).compactIfNeeded(messages, model, null);

        assertThat(outcome).isNotNull();
        assertThat(outcome.summarized()).isTrue();
        assertThat(outcome.shadowedMessages()).isEqualTo(5);
        assertThat(model.calls).isEqualTo(1);
        // 重建后：system + 首条 user + 摘要节点 + 保留的尾部（4 条，原 7 条）
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) messages.get(1)).getText()).contains("首条任务上下文（必须保留）");
        assertThat(messages.get(2).getText())
                .contains("<compacted-history")
                .contains("## 已完成")
                .contains("取代原文");
        assertThat(messages.get(3)).isSameAs(lastTail);
        assertThat(before).isEqualTo(8);
    }

    @Test
    @DisplayName("净收益守卫：摘要比被压区间还长 → 回滚原文、返回 null（实测暴露的 4939→6327 场景）")
    void shouldRevertSummaryWithoutNetGain() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("任务"));
        for (int i = 0; i < 5; i++) {
            messages.add(new AssistantMessage(variedText(200, "a")));
        }
        messages.add(new AssistantMessage("最近一轮 " + variedText(200, "t")));
        List<Message> snapshot = new ArrayList<>(messages);
        // 摘要比被压区间还长 ⇒ 无净收益
        StubChatModel verbose = new StubChatModel().enqueue(textResponse(variedText(3000, "s")));

        assertThat(new ContextCompactor(props()).compactIfNeeded(messages, verbose, null)).isNull();
        assertThat(messages).hasSameSizeAs(snapshot);
        assertThat(((AssistantMessage) messages.get(1)).getText()).isEqualTo(variedText(200, "a"));
    }

    @Test
    @DisplayName("摘要额度=0 → 只做确定性剪枝、不开摘要（防每轮都试一次）")
    void shouldHonorSummarizationBudget() {
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("任务"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "t", variedText(500, "y")))).build(),
                new AssistantMessage(variedText(200, "a")),
                new AssistantMessage(variedText(200, "b"))));
        StubChatModel model = new StubChatModel();

        CompactionOutcome outcome = new ContextCompactor(props()).compactIfNeeded(messages, model, null, 0);

        assertThat(outcome).isNotNull();
        assertThat(outcome.prunedToolResults()).isEqualTo(1);
        assertThat(outcome.summarized()).isFalse();
        assertThat(model.calls).isZero();
    }

    @Test
    @DisplayName("摘要调用失败（模型抛错）→ 返回 null 且消息未被破坏（不阻断循环）")
    void shouldSurviveSummarizationFailure() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("任务"));
        for (int i = 0; i < 6; i++) {
            messages.add(new AssistantMessage(variedText(300, "b")));
        }
        List<Message> snapshot = new ArrayList<>(messages);
        StubChatModel failing = new StubChatModel().failNextCall();

        assertThat(new ContextCompactor(props()).compactIfNeeded(messages, failing, null)).isNull();
        assertThat(messages).hasSameSizeAs(snapshot);
        assertThat(((AssistantMessage) messages.get(1)).getText()).isEqualTo(variedText(300, "b"));
    }

    @Test
    @DisplayName("溢出路径：即使未达压力阈值也强制压缩（提供方报错后的恢复前奏）")
    void shouldForceCompactionOnOverflow() {
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("任务"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "t", variedText(500, "y")))).build()));

        CompactionOutcome outcome = new ContextCompactor(props()).compactOnOverflow(messages, new StubChatModel(), null);

        assertThat(outcome).isNotNull();
        assertThat(outcome.trigger()).isEqualTo("overflow");
        assertThat(outcome.prunedToolResults()).isEqualTo(1);
    }

    @Test
    @DisplayName("no-op 实例：maxOverflowRetries=0 且 compact 恒 null（既有单测/无 Spring 场景）")
    void noopShouldBeInert() {
        ContextCompactor noop = ContextCompactor.noop();
        List<Message> messages = new ArrayList<>(List.of(new UserMessage(variedText(2000, "z"))));

        assertThat(noop.maxOverflowRetries()).isZero();
        assertThat(noop.compactIfNeeded(messages, new StubChatModel(), null)).isNull();
        assertThat(noop.compactOnOverflow(messages, new StubChatModel(), null)).isNull();
    }

    // ────────────────────────────────────────────────────────────
    //  测试桩
    // ────────────────────────────────────────────────────────────

    /** 变体文本：重复字符会被 BPE 压成极少 token，估算与断言会失真——故用「词 + 序号」变体。 */
    private static String variedText(int words, String tag) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            sb.append(tag).append(i).append(' ');
        }
        return sb.toString();
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), null);
    }

    private static final class StubChatModel implements ChatModel {

        private final Queue<ChatResponse> responses = new LinkedList<>();
        private boolean failNext;
        int calls;

        StubChatModel enqueue(ChatResponse response) {
            responses.offer(response);
            return this;
        }

        StubChatModel failNextCall() {
            this.failNext = true;
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("provider unreachable");
            }
            return responses.poll();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }
}
