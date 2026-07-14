package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * N11 外部 Agent 阈值回退配置。
 *
 * <p>与 {@link AgentDispatchProperties} 配套：dispatch 只控制"选人策略"，
 * 本类控制"外部 Agent 失败 N 次后自动回退到平台内 API_KEY_LLM"的运行时阈值与冷却期。</p>
 *
 * <p>典型用法：{@code helloai.dispatch.fallback.failure-threshold=3}，
 * 表示同一外部 Agent 连续失败 3 次后会被 ExternalAgentFallbackTask 周期性扫描到，
 * 并将其在跑子任务重新分发给同角色的 API_KEY_LLM Agent。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.dispatch.fallback")
public class AgentFallbackProperties {

    /**
     * 总开关。
     *
     * <p>false 时 {@code recordFailure / markFallbackTriggered} 全部短路为 no-op，
     * 整套回退闭环暂停，便于在生产/回归环境快速关闭。</p>
     */
    private boolean enabled = true;

    /**
     * 连续失败次数阈值。
     *
     * <p>当 Agent.consecutive_failure_count >= 该值时，被视为回退候选。
     * 实际是否触发回退还需通过 cooldown 判定（last_fallback_at + cooldownMinutes）。</p>
     */
    private int failureThreshold = 3;

    /**
     * 回退触发后的冷却期（分钟）。
     *
     * <p>回退后写入 agent.last_fallback_at；只有当前时间晚于 last_fallback_at + cooldown
     * 时，才允许再次触发回退，避免刚被回退的 Agent 因后续任务失败而立即再次触发。</p>
     */
    private int cooldownMinutes = 10;

    /**
     * 补偿任务周期（毫秒）。
     *
     * <p>ExternalAgentFallbackTask 周期性扫描超阈值 Agent 的间隔。
     * 默认 60 秒；太小会增加 DB 扫描压力，太大会延迟回退触发。</p>
     */
    private long scanIntervalMs = 60_000L;
}
