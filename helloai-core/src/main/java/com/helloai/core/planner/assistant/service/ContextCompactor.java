package com.helloai.core.planner.assistant.service;

/**
 * 上下文压缩（PA-12）。
 *
 * <p>自动（force=false）：仅当超过 {@code trigger_ratio} 时压缩；手动（force=true）：立即压缩。
 * 压缩把中段消息折叠成滚动摘要、更新 {@code compacted_until_seq}，<b>不删除原始消息</b>。</p>
 */
public interface ContextCompactor {

    /**
     * @param compacted      是否实际执行了压缩
     * @param evictedMessages 本次折叠的消息条数
     * @param reason         未压缩原因（below_threshold / disabled / nothing_to_evict / summary_failed / compacted）
     */
    record CompactResult(boolean compacted, int evictedMessages, int compactedUntilSeq,
                         int summaryVersion, double beforeRatio, double afterRatio, String reason) {
    }

    CompactResult compact(Long conversationId, boolean force);
}