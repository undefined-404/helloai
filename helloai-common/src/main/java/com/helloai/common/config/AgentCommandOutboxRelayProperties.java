package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 2H ②a 引入：
 * 执行命令 Outbox Relay 任务的可调参数。
 *
 * <p>本类是 {@code OutboxRelayTask}（helloai-job）的运行开关与节奏参数，
 * 全部带合理默认值，开箱即用。修改无需重启以外的额外动作。</p>
 *
 * <pre>
 * helloai:
 *   outbox:
 *     relay:
 *       enabled: true                     # Relay 周期任务是否启用；dispatch-mode ∈ {MQ,BOTH} 时 Validator 会强制要求 enabled
 *       interval-ms: 1000                 # 扫描周期（毫秒）
 *       batch-limit: 50                   # 单批扫描上限
 *       max-retry: 5                      # 单条 outbox 最多重试次数；超阈值标记 FAILED
 *       base-backoff-seconds: 2           # 失败后下次重试的指数退避基数（秒）
 * </pre>
 *
 * <p>本轮明确不做：<br>
 * (1) CONFIRMED 状态机扩展——属于 ②b publisher-confirms；<br>
 * (2) per-eventId 重试预算之外的"业务级熔断"——本轮未引入；<br>
 * (3) 分区/批量并行扫描——本轮单线程顺序执行；最小闭环不需要。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.outbox.relay")
public class AgentCommandOutboxRelayProperties {

    /** Relay 周期任务总开关；默认开启。 */
    private boolean enabled = true;

    /** 扫描周期（毫秒）。 */
    private long intervalMs = 1000L;

    /** 单次扫描行数上限；防止单批过慢阻塞调度线程。 */
    private int batchLimit = 50;

    /** 单条 outbox 行最多重试次数；超过则标记 FAILED 不再扫描。 */
    private int maxRetry = 5;

    /**
     * 失败后指数退避基数（秒）。
     * 实际间隔 = base * 2^retryCount；retry_count 递增避免雷暴。
     */
    private int baseBackoffSeconds = 2;

    private int confirmTimeoutSeconds = 30;
}
