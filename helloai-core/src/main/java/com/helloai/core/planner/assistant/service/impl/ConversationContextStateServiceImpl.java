package com.helloai.core.planner.assistant.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.planner.assistant.entity.ConversationContextState;
import com.helloai.core.planner.assistant.service.ConversationContextStateService;
import com.helloai.core.planner.mapper.ConversationContextStateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * {@link ConversationContextStateService} 实现（PA-12）。
 *
 * <p>初始化默认：{@code compacted_until_seq=0}、{@code trigger_ratio=0.800}、
 * {@code reserve_ratio=0.200}、{@code enabled=1}。</p>
 */
@Slf4j
@Service
public class ConversationContextStateServiceImpl
        extends ServiceImpl<ConversationContextStateMapper, ConversationContextState>
        implements ConversationContextStateService {

    private static final BigDecimal DEFAULT_TRIGGER = new BigDecimal("0.800");
    private static final BigDecimal DEFAULT_RESERVE = new BigDecimal("0.200");

    @Override
    public ConversationContextState getByConversation(Long conversationId) {
        return lambdaQuery()
                .eq(ConversationContextState::getConversationId, conversationId)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public ConversationContextState getOrInit(Long conversationId, Long ownerId) {
        ConversationContextState existing = lambdaQuery()
                .eq(ConversationContextState::getConversationId, conversationId)
                .last("LIMIT 1")
                .one();
        if (existing != null) {
            return existing;
        }
        ConversationContextState state = new ConversationContextState();
        state.setConversationId(conversationId);
        state.setCompactedUntilSeq(0);
        state.setSummaryVersion(0);
        state.setTriggerRatio(DEFAULT_TRIGGER);
        state.setReserveRatio(DEFAULT_RESERVE);
        state.setEnabled(1);
        state.setOwnerId(ownerId);
        try {
            save(state);
            return state;
        } catch (Exception e) {
            // 并发初始化撞唯一索引：回查已存在行（best-effort）
            log.warn("上下文状态初始化冲突，回查既有行: conversationId={}, err={}", conversationId, e.getMessage());
            ConversationContextState reloaded = lambdaQuery()
                    .eq(ConversationContextState::getConversationId, conversationId)
                    .last("LIMIT 1")
                    .one();
            if (reloaded != null) {
                return reloaded;
            }
            throw e;
        }
    }
}