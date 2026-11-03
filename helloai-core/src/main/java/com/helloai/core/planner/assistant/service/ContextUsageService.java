package com.helloai.core.planner.assistant.service;

import java.time.OffsetDateTime;

/**
 * 上下文窗口占用计量（PA-12）。
 *
 * <p>五项拆分：系统提示词 / 工具 / 技能 / 消息（含摘要） / 其他；比率 = 总 token / 上下文窗口。
 * token 由 {@code TokenEstimator} 估算（模型返回的 {@code tokenUsage} 可事后校准）。</p>
 */
public interface ContextUsageService {

    /** 上下文窗口占用快照。 */
    record ContextUsage(long systemPromptTokens,
                        long toolTokens,
                        long skillTokens,
                        long messageTokens,
                        long otherTokens,
                        long totalTokens,
                        int contextSize,
                        double ratio,
                        int compactedUntilSeq,
                        int summaryVersion,
                        OffsetDateTime lastCompactAt) {
    }

    /** 计算某会话的上下文占用（owner 不匹配时抛业务异常）。 */
    ContextUsage usage(Long conversationId);
}