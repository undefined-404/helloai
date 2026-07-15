package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 2H ②a 引入 / Phase 2H ②b 扩展：
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
 *       confirm-timeout-seconds: 30       # ②b：SENT 后超过该秒数未确认即视为超时，自动回退到 PENDING 重试
 * </pre>
 *
 * <p>历史明确不做：
 * <ul>
 *   <li>Poller 降级为兜底——T5 才推进，本轮 Relay 仍是 MQ 主投递载体；</li>
 *   <li>{@code OutboxCompensationTask} 新增调度——本轮直接复用 {@code OutboxRelayTask}，不引入新的 Scheduled；</li>
 *   <li>DLQ 与 per-eventId 业务级熔断——本轮未引入；</li>
 *   <li>分区/批量并行扫描——本轮单线程顺序执行；最小闭环不需要。</li>
 * </ul>
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
