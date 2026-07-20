package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 健康与离线判定统一配置。
 *
 * <p>本类是项目中"Agent 是否视为在线/可用"的单一阈值来源（v2.6 §4.1 调度链修复）。
 * 用于消除以下两套阈值漂移问题：</p>
 * <ul>
 *     <li>{@code AgentHealthCheckTask} 原硬编码 5 分钟 Reconcile 阈值</li>
 *     <li>{@code AgentDispatchProperties.heartbeatFreshMinutes} 原 10 分钟 Selector 阈值</li>
 * </ul>
 *
 * <p>统一阈值为 5 分钟，对齐 Redis 心跳 TTL（{@code helloai.heartbeat.ttl-seconds}）。
 * core（Selector、ExternalAgentFailureTracker）和 job（AgentHealthCheckTask）共同注入本配置，
 * 避免 Java 侧过滤和 SQL 侧过滤再次漂移。</p>
 *
 * <p>注意：{@code API_KEY_LLM} 接入类型按架构 §3.8 三层可用性约束豁免，
 * 始终视为心跳新鲜，不需要运行时心跳。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.agent.health")
public class AgentHealthProperties {

    /**
     * Agent 心跳离线阈值（分钟）。
     *
     * <p>从 {@code last_seen_time} 距今超过本阈值即视为离线 / 心跳过期。
     * 默认 5 分钟，与 Redis 心跳 TTL 对齐。</p>
     *
     * <p>取值说明：</p>
     * <ul>
     *     <li>推荐值：5（对齐 Redis TTL）</li>
     *     <li>设置为 0 或负数：关闭心跳新鲜度过滤（不推荐；Selector 与 Reconcile 仍会基于 online_status 工作）</li>
     * </ul>
     */
    private int offlineMinutes = 5;
}