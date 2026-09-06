package com.helloai.core.planner.memory;

import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;

import java.util.List;

/**
 * 会话摘要生成器（N-009，C5-S2，纯函数可单测）。
 *
 * <p>规则抽取（首版确定性实现）：标题 + 用户消息要点（前 {@code MAX_USER_POINTS} 条，
 * 每条截断）。原则兑现：只存摘要，不存原始对话全量；LLM 压缩列为后续优化
 * （本类为替换点，保持接口稳定）。</p>
 */
public final class ConversationMemorySummarizer {

    private static final int MAX_USER_POINTS = 5;
    private static final int POINT_MAX_CHARS = 200;
    private static final int TAG_MAX_CHARS = 64;

    private ConversationMemorySummarizer() {
    }

    /** 摘要结果。 */
    public record MemorySummary(String title, String content, String tag) {
    }

    /**
     * 生成会话摘要。
     *
     * @param conversation 会话（title/finalTitle 来源）
     * @param messages     会话消息（按 seq 顺序）
     */
    public static MemorySummary summarize(RequirementConversation conversation, List<RequirementMessage> messages) {
        String title = conversation.getTitle();
        if (title == null || title.isBlank()) {
            title = "（无标题）";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (messages != null) {
            for (RequirementMessage msg : messages) {
                if (count >= MAX_USER_POINTS) {
                    break;
                }
                if (msg != null && "user".equals(msg.getRole())
                        && msg.getContent() != null && !msg.getContent().isBlank()) {
                    sb.append("- ").append(truncate(msg.getContent(), POINT_MAX_CHARS)).append('\n');
                    count++;
                }
            }
        }
        if (count == 0) {
            sb.append("（无用户消息要点）");
        }
        String tag = truncate(title, TAG_MAX_CHARS);
        return new MemorySummary(title, sb.toString().trim(), tag);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
