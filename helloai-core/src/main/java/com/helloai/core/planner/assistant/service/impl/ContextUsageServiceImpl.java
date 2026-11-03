package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ContextWindowProperties;
import com.helloai.core.planner.assistant.context.TokenEstimator;
import com.helloai.core.planner.assistant.entity.ConversationContextState;
import com.helloai.core.planner.assistant.service.ConversationContextStateService;
import com.helloai.core.planner.assistant.service.ContextUsageService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.shared.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * {@link ContextUsageService} 实现（PA-12）。
 *
 * <p>消息 token = 摘要 + {@code compacted_until_seq} 之后仍进入 prompt 的消息；
 * 系统提示词 / 工具 / 技能取配置估算值（v1）。</p>
 */
@Service
@RequiredArgsConstructor
public class ContextUsageServiceImpl implements ContextUsageService {

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final ConversationContextStateService stateService;
    private final ContextWindowProperties properties;

    @Override
    public ContextUsage usage(Long conversationId) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("会话不存在: " + conversationId);
        }
        requireOwner(conversation);
        ConversationContextState state = stateService.getOrInit(conversationId, conversation.getOwnerId());

        int compactedUntil = state.getCompactedUntilSeq() == null ? 0 : state.getCompactedUntilSeq();
        List<String> liveContents = messageService.listByConversation(conversationId).stream()
                .filter(m -> m.getSeq() != null && m.getSeq() > compactedUntil)
                .map(RequirementMessage::getContent)
                .toList();
        long messageTokens = TokenEstimator.estimateText(state.getSummary(),
                properties.getCharsPerToken(), 0)
                + TokenEstimator.estimateMessages(liveContents,
                properties.getCharsPerToken(), properties.getMessageOverhead());

        long system = properties.getSystemPromptTokens();
        long tool = properties.getToolTokens();
        long skill = properties.getSkillTokens();
        long other = 0;
        long total = system + tool + skill + messageTokens + other;
        int contextSize = state.getContextSize() != null && state.getContextSize() > 0
                ? state.getContextSize() : properties.getContextSize();
        double ratio = contextSize > 0 ? (double) total / contextSize : 0d;
        return new ContextUsage(system, tool, skill, messageTokens, other, total, contextSize, ratio,
                compactedUntil, state.getSummaryVersion() == null ? 0 : state.getSummaryVersion(),
                state.getLastCompactAt());
    }

    private static void requireOwner(RequirementConversation conversation) {
        Long userId = CurrentUser.userId();
        if (userId != null && conversation.getOwnerId() != null
                && !Objects.equals(conversation.getOwnerId(), userId)) {
            throw new BizException(403, "无权访问该会话");
        }
    }
}