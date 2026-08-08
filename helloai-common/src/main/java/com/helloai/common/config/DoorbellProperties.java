package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 长连接门铃通知通道配置（AgentHub V3 门铃内核 PR-1）。
 *
 * <p><b>状态注记（2026-08-07）</b>：门铃通道已搁置。技术瓶颈——外部 AI Agent
 * （安装版 / CLI 版）均为单向执行器，无法处理平台推送的门铃信号，且 Agent 端代码
 * 不可修改；任务感知一律由 pullTasks 轮询承担。本配置保留，待未来 Agent 端
 * 常驻 daemon（官方插件 / CLI 包装器）落地后可复用本通道。</p>
 *
 * <p>门铃是"服务端 → 外部 Agent"的单向 SSE 长连接，只推送轻量唤醒信号，
 * 不承载任务正文，详见 {@code doc/HelloAI_门铃通知通道设计.md}。</p>
 *
 * <p>本配置集中管理门铃运行参数，仿 {@link AgentExecutionProperties} 风格，
 * 避免连接超时、保活间隔等散落在业务代码里。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.doorbell")
public class DoorbellProperties {

    /** 是否启用门铃通道。关闭时建连请求直接拒绝，Agent 回退到 pullTasks 轮询。 */
    private boolean enabled = true;

    /**
     * 单条 SSE 门铃连接的空闲超时（毫秒）。默认 30 分钟。
     *
     * <p>超时后 {@code SseEmitter} 触发 onTimeout，连接被清理，
     * 客户端应负责重连；重连空窗由 pullTasks 轮询兜底。</p>
     */
    private long emitterTimeoutMs = 1_800_000L;

    /**
     * 服务端保活帧间隔（毫秒）。默认 15 秒。
     *
     * <p>PR-4 起启用：{@code DoorbellKeepaliveTask} 按此间隔（{@code @Scheduled fixedRateString}）
     * 向本进程所有活跃连接广播一帧 {@code keepalive}，穿透反代空闲连接超时，
     * 避免长连接被中间层提前切断。</p>
     */
    private long keepaliveIntervalMs = 15_000L;

    /**
     * 建连时是否顺带刷新 Agent 心跳（last_seen_at），即“双心跳”（PR-4，设计 §6.2/§7）。
     *
     * <p><b>默认 false（保守）</b>：门铃连接存活 ≠ Agent 进程健康（SSE 单向，TCP 存活
     * 不代表对端应用消费了），自动刷心跳会让僵尸连接掩盖真实离线，故默认不启用。</p>
     *
     * <p>开启后：仅在 {@code connect()} 建连时调一次 {@code HeartbeatService.seen()}（建连是
     * 客户端主动、最可信的存活证据），keepalive 保活轮<b>不</b>刷；可降低 Agent 自身
     * heartbeat 调用频率。Agent 自身 heartbeat 仍作为持续存活的权威来源。</p>
     */
    private boolean refreshHeartbeat = false;
}
