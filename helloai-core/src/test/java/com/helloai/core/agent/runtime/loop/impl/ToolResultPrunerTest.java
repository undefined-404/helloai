package com.helloai.core.agent.runtime.loop.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolResultPruner} 单测（REF-8.2.3，对齐 DSH {@code compaction-tool-result-pruner}）。
 *
 * <p>口径断言：默认阈值 8192 / 头 4096 / 尾 1024；标记文案与 DSH 一致；**按 Unicode 码点计长**。</p>
 */
@DisplayName("ToolResultPruner 工具结果剪枝（REF-8.2.3）")
class ToolResultPrunerTest {

    @Test
    @DisplayName("未超阈值 → 原样返回（不做任何改写）")
    void shouldKeepShortContent() {
        String content = "短结果";
        assertThat(ToolResultPruner.prune(content, 8192, 4096, 1024)).isEqualTo(content);
    }

    @Test
    @DisplayName("超阈值 → 头 4096 码点 + 标记 + 尾 1024 码点（ASCII 长串）")
    void shouldPruneMiddle() {
        String head = "H".repeat(4096);
        String middle = "M".repeat(5000);
        String tail = "T".repeat(1024);
        String content = head + middle + tail;

        String pruned = ToolResultPruner.prune(content, 8192, 4096, 1024);

        assertThat(pruned).startsWith(head).endsWith(tail).contains(ToolResultPruner.PRUNE_MARKER);
        assertThat(pruned).doesNotContain("MMMM");
    }

    @Test
    @DisplayName("按 Unicode 码点计长：中文按「字」不按字节（同一阈值下与 ASCII 同口径）")
    void shouldCountByCodePoint() {
        // 8192 个中文码点 = 8192 字符（UTF-16 单元数也是 8192，但字节数是 24576）——恰好不超阈值
        String justAtThreshold = "中".repeat(8192);
        assertThat(ToolResultPruner.prune(justAtThreshold, 8192, 4096, 1024)).isEqualTo(justAtThreshold);

        // 超一个码点即剪枝，且不切断代理对（emoji 占 2 个 UTF-16 单元）
        String overThreshold = "中".repeat(8192) + "🙂";
        String pruned = ToolResultPruner.prune(overThreshold, 8192, 4096, 1024);
        assertThat(pruned).contains(ToolResultPruner.PRUNE_MARKER);
        assertThat(pruned).doesNotContain("�");   // 无替换字符 ⇒ 未切断代理对
    }

    @Test
    @DisplayName("pruneMessages：只改写超限的工具结果，保留 id/name；非工具消息逐字不动")
    void shouldPruneOnlyOversizedToolResponses() {
        ToolResponseMessage.ToolResponse small = new ToolResponseMessage.ToolResponse("c1", "internal_read_attachment", "短");
        ToolResponseMessage.ToolResponse big = new ToolResponseMessage.ToolResponse(
                "c2", "internal_get_deps_summary", "X".repeat(300));
        List<Message> messages = new ArrayList<>(List.of(
                new UserMessage("任务"),
                new AssistantMessage("思考"),
                ToolResponseMessage.builder().responses(List.of(small, big)).build()));

        int rewritten = ToolResultPruner.pruneMessages(messages, 100, 20, 10);

        assertThat(rewritten).isEqualTo(1);
        ToolResponseMessage after = (ToolResponseMessage) messages.get(2);
        assertThat(after.getResponses().get(0).responseData()).isEqualTo("短");
        assertThat(after.getResponses().get(1).id()).isEqualTo("c2");
        assertThat(after.getResponses().get(1).name()).isEqualTo("internal_get_deps_summary");
        assertThat(after.getResponses().get(1).responseData()).contains(ToolResultPruner.PRUNE_MARKER);
        assertThat(((UserMessage) messages.get(0)).getText()).isEqualTo("任务");
    }

    @Test
    @DisplayName("pruneMessages：全部未超限 → 返回 0 且列表引用不变（调用方可据此跳过）")
    void shouldReportNoChange() {
        List<Message> messages = new ArrayList<>(List.of(
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "t", "short")))
                        .build()));

        assertThat(ToolResultPruner.pruneMessages(messages, 8192, 4096, 1024)).isZero();
    }
}
