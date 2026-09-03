package com.helloai.common.constant;

import com.helloai.common.base.AgentUnavailableException;

import java.io.IOException;

/**
 * 重试统一预算规则（Phase 0 A3，坑点 3「单一权威」）。
 *
 * <p>同一子任务的所有重试共享一个全局计数器 {@code sub_task.attempt_total}，
 * 每一层在重试前都必须先检查预算是否耗尽，杜绝「重分配 5 × 返工 N × 投递 N」
 * 多层独立计数叠加导致实际执行次数失控（Outbox 3 × 重分配 5 等组合可放大到 45 次）。</p>
 *
 * <p>上限值不在此处新建配置（规范 §2.3/§40）：调用方直接复用现有配置
 * {@code helloai.dispatch.max-reassign-attempts}（默认 5）作为统一预算。</p>
 *
 * <p>本类只保留「判定」语义、不持有状态；退避时序仍由各层自身机制控制
 * （如 Outbox 的 {@code next_retry_time} 指数退避窗口），避免重复抽象。</p>
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /**
     * 预算耗尽判定：{@code attempt >= maxAttempts} 即禁止继续重试。
     *
     * @param maxAttempts 上限；&lt;= 0 表示熔断禁用逃生口、永不熔断（与
     *                    {@code helloai.dispatch.max-reassign-attempts <= 0} 语义一致）
     */
    public static boolean exceedsMax(int attempt, int maxAttempts) {
        return maxAttempts > 0 && attempt >= maxAttempts;
    }

    /**
     * 可重试错误分类：连接类 / 超时类 / Agent 不可用类错误允许重试。
     *
     * <p>业务语义错误（参数错误、状态机非法流转等）一律不可重试——
     * 重试只会放大错误，必须走人工 / 死信兜底。</p>
     */
    public static boolean isRetryable(Throwable t) {
        if (t == null) {
            return false;
        }
        // IOException 含子类 SocketTimeoutException / ConnectException
        return t instanceof IOException
                || t instanceof AgentUnavailableException
                || t.getCause() != null && isRetryable(t.getCause());
    }
}