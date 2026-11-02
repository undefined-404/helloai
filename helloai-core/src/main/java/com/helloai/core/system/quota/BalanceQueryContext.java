package com.helloai.core.system.quota;

/**
 * 单次余额查询的运行时上下文，透传给 {@link BalanceProvider} 的
 * {@code supports} / {@code fetch}。
 */
public record BalanceQueryContext(
        String providerKey,
        int fetchTimeoutMillis
) {
}