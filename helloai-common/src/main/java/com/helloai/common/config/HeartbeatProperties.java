package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 心跳配置（对话并发优化 A 项：active() 写节流）。
 *
 * <p>背景：每轮对话 LLM 前 {@code HeartbeatService.active()} 会对同一 Agent 行做
 * 2 次 selectById + 2 次全字段 updateById；多个对话并发钉住同一 Planner 时，
 * InnoDB 行锁排队 + 纯浪费的 DB 写放大。节流后同一 Agent 在窗口内只执行一次完整双写。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.heartbeat")
public class HeartbeatProperties {

    /**
     * {@code active()} 节流窗口（毫秒）：同一 agentId 在窗口内只执行一次完整双写
     * （Redis TTL + DB last_seen_time + last_active_time）。
     * <p>last_active_time 仅用于 ONLINE/IDLE 判定（5 分钟窗口），默认 30s 节流误差无实质影响；
     * 外部 Agent 的 last_seen_time 由其 2 秒级 heartbeat 工具独立保活，不受本窗口影响。
     * &lt;=0 表示不节流（保持原行为）。</p>
     */
    private long activeThrottleMs = 30_000L;
}
