package com.helloai.core.planner.assistant.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.ContextWindowProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.assistant.context.TokenEstimator;
import com.helloai.core.planner.assistant.entity.ConversationContextState;
import com.helloai.core.planner.assistant.service.ConversationContextStateService;
import com.helloai.core.planner.assistant.service.ContextCompactor;
import com.helloai.core.planner.assistant.service.ContextUsageService;
import com.helloai.core.planner.assistant.service.ContextUsageService.ContextUsage;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link ContextCompactor} 实现（PA-12）。
 *
 * <p>渐进压缩的 v1 形态：计算占用 → 判定阈值 → 保留尾部（{@code reserve_ratio}）→
 * 把中段交给 LLM 生成滚动摘要 → 更新 {@code compacted_until_seq}。原始消息不动，可回查。
 * 摘要失败保留上一版并返回未压缩原因，不阻断对话。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCompactorServiceImpl implements ContextCompactor {

    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是对话摘要助手。把给定对话压缩成简洁要点，保留关键事实、决定与未完成事项；"
                    + "不要编造，不要输出寒暄。只输出纯文本摘要，不超过 400 字。";

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;
    private final ConversationContextStateService stateService;
    private final ContextUsageService usageService;
    private final ContextWindowProperties properties;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;

    @Override
    public CompactResult compact(Long conversationId, boolean force) {
        RequirementConversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new BizException("会话不存在: " + conversationId);
        }
        ConversationContextState state = stateService.getOrInit(conversationId, conversation.getOwnerId());
        ContextUsage before = usageService.usage(conversationId);

        if (!force) {
            boolean enabled = state.getEnabled() == null || state.getEnabled() == 1;
            if (!properties.isEnabled() || !enabled) {
                return notCompacted("disabled", before, state);
            }
            double trigger = state.getTriggerRatio() != null
                    ? state.getTriggerRatio().doubleValue() : properties.getTriggerRatio();
            if (before.ratio() <= trigger) {
                return notCompacted("below_threshold", before, state);
            }
        }

        int compactedUntil = state.getCompactedUntilSeq() == null ? 0 : state.getCompactedUntilSeq();
        List<RequirementMessage> live = liveMessages(conversationId, compactedUntil);
        if (live.isEmpty()) {
            return notCompacted("nothing_to_evict", before, state);
        }

        int contextSize = state.getContextSize() != null && state.getContextSize() > 0
                ? state.getContextSize() : properties.getContextSize();
        double reserveRatio = state.getReserveRatio() != null
                ? state.getReserveRatio().doubleValue() : properties.getReserveRatio();
        long reserveTokens = Math.max(1, (long) Math.floor(contextSize * reserveRatio));

        // 从尾部向前累计，保留至少 reserveTokens 的最近消息（至少保留最后一条）
        int tailStart = live.size() - 1;
        long accumulated = 0;
        for (int i = live.size() - 1; i >= 0; i--) {
            accumulated += TokenEstimator.estimateText(live.get(i).getContent(),
                    properties.getCharsPerToken(), properties.getMessageOverhead());
            tailStart = i;
            if (accumulated >= reserveTokens) {
                break;
            }
        }
        List<RequirementMessage> evictable = live.subList(0, tailStart);
        if (evictable.isEmpty()) {
            return notCompacted("nothing_to_evict", before, state);
        }
        int cutoffSeq = evictable.get(evictable.size() - 1).getSeq();

        String summary = summarize(conversation, state, evictable);
        if (summary == null) {
            return notCompacted("summary_failed", before, state);
        }
        state.setSummary(summary);
        state.setCompactedUntilSeq(cutoffSeq);
        state.setSummaryVersion((state.getSummaryVersion() == null ? 0 : state.getSummaryVersion()) + 1);
        state.setLastCompactAt(OffsetDateTime.now());
        stateService.updateById(state);

        ContextUsage after = usageService.usage(conversationId);
        log.info("上下文压缩完成: conversationId={}, evicted={}, compactedUntilSeq={}, summaryVersion={}, ratio {}->{}",
                conversationId, evictable.size(), cutoffSeq, state.getSummaryVersion(),
                before.ratio(), after.ratio());
        return new CompactResult(true, evictable.size(), cutoffSeq, state.getSummaryVersion(),
                before.ratio(), after.ratio(), "compacted");
    }

    private List<RequirementMessage> liveMessages(Long conversationId, int compactedUntil) {
        return messageService.listByConversation(conversationId).stream()
                .filter(m -> m.getSeq() != null && m.getSeq() > compactedUntil)
                .sorted(Comparator.comparing(RequirementMessage::getSeq))
                .toList();
    }

    /** 生成滚动摘要；失败返回 null（保留上一版）。 */
    private String summarize(RequirementConversation conversation, ConversationContextState state,
                             List<RequirementMessage> evictable) {
        String input = buildSummaryInput(state.getSummary(), evictable);
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask task = AgentTask.builder()
                .systemPrompt(SUMMARY_SYSTEM_PROMPT)
                .userPrompt(input)
                .context(Map.of("conversationId", conversation.getId(), "scene", "context_compact"))
                .requiredCapabilities(Map.of())
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(planner.id(), task);
        if (result == null || !result.isSuccess()
                || result.getOutput() == null || result.getOutput().isBlank()) {
            log.warn("上下文压缩摘要失败，保留上一版: conversationId={}, reason={}",
                    conversation.getId(), result == null ? "null" : result.getErrorMessage());
            return null;
        }
        return result.getOutput().trim();
    }

    private String buildSummaryInput(String previous, List<RequirementMessage> evictable) {
        StringBuilder sb = new StringBuilder();
        if (previous != null && !previous.isBlank()) {
            sb.append("已有摘要：\n").append(previous).append("\n\n新增对话：\n");
        } else {
            sb.append("对话记录：\n");
        }
        int max = Math.max(1000, properties.getSummaryMaxChars());
        for (RequirementMessage message : evictable) {
            sb.append(message.getRole()).append("：")
                    .append(message.getContent() == null ? "" : message.getContent()).append('\n');
            if (sb.length() >= max) {
                break;
            }
        }
        if (sb.length() > max) {
            sb.setLength(max);
        }
        return sb.toString();
    }

    private static CompactResult notCompacted(String reason, ContextUsage before,
                                              ConversationContextState state) {
        return new CompactResult(false, 0,
                state.getCompactedUntilSeq() == null ? 0 : state.getCompactedUntilSeq(),
                state.getSummaryVersion() == null ? 0 : state.getSummaryVersion(),
                before.ratio(), before.ratio(), reason);
    }
}