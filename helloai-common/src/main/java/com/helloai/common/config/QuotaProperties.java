package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 额度 / 用量监控配置（G-026，`D-2026-11-02` 系列）。
 *
 * <p>所有参数都可配且带默认值，见设计 §4.5。缓存落地 Redis（RBucket）以支持多实例
 * 不重复打外部接口；熔断走 Resilience4j {@code quotaFetch} 实例。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.quota")
public class QuotaProperties {

    /** 抓取开关。关闭时定时采集不跑；前端可见性由 {@code sys_config["quota.widget.enabled"]} 控制。 */
    private boolean enabled = true;

    /** 成功快照 Redis 缓存 TTL（秒）。默认 60s。 */
    private long cacheTtlSeconds = 60L;

    /** 失败态 Redis 缓存 TTL（秒）。默认 30s，失败时避免反复重试。 */
    private long failureCacheTtlSeconds = 30L;

    /** 单家抓取超时（毫秒）。默认 5s（与 deepseek.connect-timeout-ms 同量级）。 */
    private long fetchTimeoutMs = 5_000L;

    /** 定时采集间隔（毫秒）。默认 15 分钟。 */
    private long collectIntervalMs = 900_000L;

    /** 快照保留天数。默认 30 天，超期由定时任务清理。 */
    private int snapshotRetentionDays = 30;
}