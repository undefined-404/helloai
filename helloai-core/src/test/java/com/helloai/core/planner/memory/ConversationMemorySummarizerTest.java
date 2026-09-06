package com.helloai.core.planner.memory;

import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConversationMemorySummarizer} C5-S2 单元测试：会话摘要规则抽取。
 */
@DisplayName("ConversationMemorySummarizer 会话摘要（C5-S2）")
class ConversationMemorySummarizerTest {

    private RequirementConversation conversation(String title) {
        RequirementConversation c = new RequirementConversation();
        c.setId(1L);
        c.setTitle(title);
        return c;
    }

    private RequirementMessage message(String role, String content) {
        RequirementMessage m = new RequirementMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("摘要：标题 + 用户消息要点（最多 5 条，助手消息跳过）")
    void summarizeWithUserPoints() {
        List<RequirementMessage> messages = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            messages.add(message("user", "需求点 " + i));
        }
        messages.add(message("assistant", "助手回复，不应进入摘要"));

        ConversationMemorySummarizer.MemorySummary summary =
                ConversationMemorySummarizer.summarize(conversation("日报模块"), messages);

        assertThat(summary.title()).isEqualTo("日报模块");
        assertThat(summary.content()).contains("- 需求点 1");
        assertThat(summary.content()).contains("- 需求点 5");
        // 只取前 5 条用户消息
        assertThat(summary.content()).doesNotContain("需求点 6");
        assertThat(summary.content()).doesNotContain("助手回复");
        assertThat(summary.tag()).isEqualTo("日报模块");
    }

    @Test
    @DisplayName("无标题 → 占位标题；无用户消息 → 占位要点")
    void summarizeEmpty() {
        ConversationMemorySummarizer.MemorySummary summary =
                ConversationMemorySummarizer.summarize(conversation("  "), List.of());

        assertThat(summary.title()).isEqualTo("（无标题）");
        assertThat(summary.content()).isEqualTo("（无用户消息要点）");
    }

    @Test
    @DisplayName("超长用户消息截断")
    void summarizeTruncates() {
        String longMsg = "很长的需求描述".repeat(100);
        ConversationMemorySummarizer.MemorySummary summary =
                ConversationMemorySummarizer.summarize(conversation("t"), List.of(message("user", longMsg)));

        assertThat(summary.content()).endsWith("...");
        assertThat(summary.content()).hasSizeLessThan(longMsg.length());
    }
}
