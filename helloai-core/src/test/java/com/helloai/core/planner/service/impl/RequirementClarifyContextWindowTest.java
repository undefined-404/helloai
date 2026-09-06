package com.helloai.core.planner.service.impl;

import com.helloai.core.planner.entity.RequirementMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequirementClarifyServiceImpl#windowHistory} 纯函数单测（N-012 Context 分层收口）。
 *
 * <p>覆盖：超窗裁剪（最近 N 条 + 首条用户消息锚点前置）/ 锚点已在窗内不重复 / 窗内原样 /
 * 空列表与 null 兜底。windowHistory 为 package-private 静态方法，同包直测。</p>
 */
@DisplayName("RequirementClarifyServiceImpl.windowHistory 历史窗口裁剪（B4）")
class RequirementClarifyContextWindowTest {

    private RequirementMessage msg(String role, String content) {
        RequirementMessage m = new RequirementMessage();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private List<RequirementMessage> seq(int n) {
        List<RequirementMessage> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(msg(i == 1 ? "user" : (i % 2 == 0 ? "assistant" : "user"), "msg-" + i));
        }
        return list;
    }

    @Nested
    @DisplayName("超窗裁剪")
    class OverWindow {

        @Test
        @DisplayName("history 10 条 / 窗口 3：保留最近 3 条 + 首条 user 锚点前置（共 4 条，首元素为首条 user）")
        void shouldTrimAndKeepFirstUserAnchor() {
            List<RequirementMessage> result = RequirementClarifyServiceImpl.windowHistory(seq(10), 3);

            assertThat(result).hasSize(4);
            assertThat(result.get(0).getContent()).isEqualTo("msg-1");   // 首条 user 锚点
            assertThat(result.get(1).getContent()).isEqualTo("msg-8");   // 最近窗口内倒数第 3
            assertThat(result.get(3).getContent()).isEqualTo("msg-10");  // 最近窗口内末条
        }

        @Test
        @DisplayName("首条 user 锚点已在窗口内：不重复前置（返回恰好 window 条）")
        void shouldNotDuplicateAnchorWhenInsideWindow() {
            // 首条消息是 assistant，首条 user=u1 在 index 1；窗口 7 → 最近 7 条 = index1..7 含 u1
            List<RequirementMessage> history = new ArrayList<>(List.of(
                    msg("assistant", "welcome"),
                    msg("user", "u1"),
                    msg("assistant", "a2"),
                    msg("user", "u2"),
                    msg("assistant", "a3"),
                    msg("user", "u3"),
                    msg("assistant", "a4"),
                    msg("user", "u4")));

            List<RequirementMessage> result = RequirementClarifyServiceImpl.windowHistory(history, 7);

            assertThat(result).hasSize(7);
            assertThat(result.get(0).getContent()).isEqualTo("u1"); // 锚点即窗口首元素，不重复前置
            assertThat(result.stream().map(RequirementMessage::getContent))
                    .containsExactly("u1", "a2", "u2", "a3", "u3", "a4", "u4");
        }

        @Test
        @DisplayName("首条消息不是 user（历史以 assistant 开头）：锚点取第一条 user，超窗仍前置")
        void shouldFindFirstUserEvenWhenHistoryStartsWithAssistant() {
            List<RequirementMessage> history = new ArrayList<>();
            history.add(msg("assistant", "welcome"));
            history.addAll(seq(10)); // msg-1..msg-10, msg-1 是 user
            history.add(msg("assistant", "tail"));

            List<RequirementMessage> result = RequirementClarifyServiceImpl.windowHistory(history, 3);

            assertThat(result.get(0).getContent()).isEqualTo("msg-1"); // 首条 user 锚点
            assertThat(result).hasSize(4);
        }
    }

    @Nested
    @DisplayName("窗内 / 边界")
    class WithinWindow {

        @Test
        @DisplayName("size ≤ window：原样返回（同一引用）")
        void shouldReturnAsIsWithinWindow() {
            List<RequirementMessage> history = seq(3);

            List<RequirementMessage> result = RequirementClarifyServiceImpl.windowHistory(history, 40);

            assertThat(result).isSameAs(history);
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("空列表：原样返回空")
        void shouldReturnEmptyForEmptyList() {
            List<RequirementMessage> empty = List.of();

            assertThat(RequirementClarifyServiceImpl.windowHistory(empty, 40)).isSameAs(empty);
        }

        @Test
        @DisplayName("null：返回 null（调用方防御）")
        void shouldReturnNullForNull() {
            assertThat(RequirementClarifyServiceImpl.windowHistory(null, 40)).isNull();
        }
    }
}
