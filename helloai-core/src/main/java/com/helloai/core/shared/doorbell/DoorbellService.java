package com.helloai.core.shared.doorbell;

import com.helloai.common.base.BizException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 门铃服务（AgentHub 门铃内核 PR-1）。
 *
 * <p><b>状态注记</b>：门铃通道已搁置。技术瓶颈——外部 AI Agent
 * （安装版 / CLI 版）均为单向执行器，无法处理平台推送的门铃信号，且 Agent 端代码
 * 不可修改；任务感知一律由 pullTasks 轮询承担。本代码保留运行，待未来 Agent 端
 * 常驻 daemon（官方插件 / CLI 包装器）落地后可复用本通道。</p>
 *
 * <p>门铃通道的统一入口，封装建连、响铃、断连三件事，屏蔽 {@link SseEmitter} 细节：</p>
 * <ul>
 *   <li>{@link #connect(Long)}：为 Agent 建立 SSE 门铃连接并回推握手信号；</li>
 *   <li>{@link #ring(Long, DoorbellSignal)}：向指定 Agent 响铃（尽力而为，失败静默降级）；</li>
 *   <li>{@link #disconnect(Long)}：主动断开某 Agent 的门铃连接。</li>
 * </ul>
 *
 * <p><b>可靠性原则</b>：门铃永远只是"催一下"，响铃失败/无连接一律静默——消息事实
 * 早已落 {@code agent_inbox}，Agent 始终可用 pullTasks 轮询兜底，门铃丢失不致命
 * （见 {@code doc/HelloAI_门铃通知通道设计.md} §9）。</p>
 *
 * <p>PR-3 收口值班鉴权：{@link #connect(Long)} 前置校验 {@code isOnDuty}（先打卡再接电话）；
 * checkOut / 租约到期时由 {@code DoorbellDutyListener} 监听 {@code DutyLeaseClosedEvent} 主动 {@link #disconnect(Long)}。</p>
 *
 * <p>PR-4 保活帧调度：{@code DoorbellKeepaliveTask} 周期调用 {@link #broadcastKeepalive()}，
 * 向本进程所有活跃连接发一帧 keep-alive，穿透反代空闲超时。</p>
 */
public interface DoorbellService {

    /**
     * 为指定 Agent 建立门铃 SSE 连接。
     *
     * <p>创建 {@link SseEmitter}（超时取 {@code helloai.doorbell.emitter-timeout-ms}），
     * 注册断连清理回调，登记进 {@link DoorbellRegistry}（关旧建新），最后回推一条
     * {@code connected} 握手信号便于客户端确认门铃可用。</p>
     *
     * @param agentId 已鉴权的 Agent ID
     * @return SSE 连接，由 Spring MVC 直接作为响应体承载
     * @throws BizException 若门铃通道未启用，或 Agent 未在岗（无 ACTIVE 值班租约）
     */
    SseEmitter connect(Long agentId);

    /**
     * 向指定 Agent 响铃（尽力而为）。
     *
     * @return true=已成功送达门铃；false=Agent 未连门铃或发送失败（已静默降级，靠轮询兜底）
     */
    boolean ring(Long agentId, DoorbellSignal signal);

    /**
     * 主动断开某 Agent 的门铃连接（如 checkOut / 租约到期时调用）。
     */
    void disconnect(Long agentId);

    /** 当前门铃连接总数。 */
    int connectionCount();

    /**
     * 向本进程所有活跃门铃连接广播一帧 {@code keepalive}（PR-4 保活）。
     *
     * <p>由 {@code DoorbellKeepaliveTask} 按 {@code helloai.doorbell.keepalive-interval-ms}
     * 周期触发，穿透 Nginx / 反代的空闲连接超时，避免长连接被中间层提前切断。</p>
     *
     * <p><b>为何不选主</b>：{@link SseEmitter} 连接是进程内状态，每个实例必须保活
     * 自己 {@link DoorbellRegistry} 里的连接；不能像 {@code DutyLeaseExpirationTask} 那样
     * 用 Redis 锁选主只让一台跑，否则其他实例的连接得不到保活。</p>
     *
     * @return 本轮成功送达保活帧的连接数
     */
    int broadcastKeepalive();
}
