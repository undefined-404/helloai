package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 0 A2：子任务执行租约看门狗（Watchdog）配置。
 *
 * <p>与执行方案 A2.2 ~ A2.4 配套：{@code sub_task} 进入 IN_PROGRESS 时写入
 * {@code owner + lease_until = now + ttl}，WatchdogLeaseRenewTask 每节点独立
 * 续自己的租约，LeaseReconcilerTask 集群单例回收过期租约。</p>
 *
 * <pre>
 * helloai:
 *   agent:
 *     watchdog:
 *       enabled: true          # 租约机制总开关；false 时恢复无租约行为（不写 owner/lease_until）
 *       ttl-seconds: 300       # 单次租约有效期（秒）；到期未续即视为 Worker 崩溃
 *       renew-interval-seconds: 150  # Watchdog 续租周期（秒），建议保持 ttl 的一半
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.agent.watchdog")
public class WatchdogProperties {

    /** 租约机制总开关；false 时 changeStatus 不写 owner/lease_until，回归现状。 */
    private boolean enabled = true;

    /** 单次租约有效期（秒）；到期未续即视为 Worker 崩溃，由 Reconciler 回收。 */
    private int ttlSeconds = 300;

    /** Watchdog 续租周期（秒）；建议为 {@link #ttlSeconds} 的一半。 */
    private int renewIntervalSeconds = 150;
}