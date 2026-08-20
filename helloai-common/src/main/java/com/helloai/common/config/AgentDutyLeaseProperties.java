package com.helloai.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 值班租约自适应 TTL 配置（E1 动态 TTL 自适应，N12  第 2 段）。
 *
 * <p>租约 TTL 不再静态固定：checkIn 未显式传 TTL 时按 Agent 表现动态推断
 * （低表现 Agent 窗口短、便于快速回收；高表现 Agent 窗口长、减少续约开销）；
 * 续约路径按"是否有在跑子任务"拉长或缩短窗口（任务在跑延长、空闲缩短）。</p>
 */
@Component
@ConfigurationProperties(prefix = "helloai.agent.duty-lease")
public class AgentDutyLeaseProperties {

    /**
     * 自适应 TTL 总开关。
     *
     * <p>false 时 {@code resolveTtlMinutes} 直接返回 {@link #getDefaultTtlMinutes()}，
     * 恢复 E1 之前的静态 TTL 行为，便于生产/回归环境快速关闭。</p>
     */
    private boolean adaptiveTtlEnabled = true;

    /**
     * 动态 TTL 下限（分钟）。
     *
     * <p>低表现 Agent（score≈0 / 连续失败多）的租约窗口下限，到点后由
     * DutyLeaseExpirationTask 快速翻 EXPIRED 回收值班态。默认 5 分钟。</p>
     */
    private int minTtlMinutes = 5;

    /**
     * 动态 TTL 上限（分钟）。
     *
     * <p>高表现 Agent（score≈满分）或存在在跑子任务时的租约窗口上限，
     * 减少高频续约开销。默认 240 分钟（4 小时）。</p>
     */
    private int maxTtlMinutes = 240;

    /**
     * 默认窗口（分钟）。
     *
     * <p>自适应开关关闭、agentId 为空或 Agent 记录不存在时的兜底值，
     * 与 E1 之前 checkIn 的默认 30 分钟保持一致。</p>
     */
    private int defaultTtlMinutes = 30;

    /**
     * score 满分基准（映射锚点）。
     *
     * <p>score=0 → minTtlMinutes，score=fullScore → maxTtlMinutes，线性映射；
     * Agent 无 score 时用 consecutive_failure_count 折算表现分（每次失败 -20，下限 0）。</p>
     */
    private int fullScore = 100;

    public boolean isAdaptiveTtlEnabled() {
        return adaptiveTtlEnabled;
    }

    public void setAdaptiveTtlEnabled(boolean adaptiveTtlEnabled) {
        this.adaptiveTtlEnabled = adaptiveTtlEnabled;
    }

    public int getMinTtlMinutes() {
        return minTtlMinutes;
    }

    public void setMinTtlMinutes(int minTtlMinutes) {
        this.minTtlMinutes = minTtlMinutes;
    }

    public int getMaxTtlMinutes() {
        return maxTtlMinutes;
    }

    public void setMaxTtlMinutes(int maxTtlMinutes) {
        this.maxTtlMinutes = maxTtlMinutes;
    }

    public int getDefaultTtlMinutes() {
        return defaultTtlMinutes;
    }

    public void setDefaultTtlMinutes(int defaultTtlMinutes) {
        this.defaultTtlMinutes = defaultTtlMinutes;
    }

    public int getFullScore() {
        return fullScore;
    }

    public void setFullScore(int fullScore) {
        this.fullScore = fullScore;
    }
}
