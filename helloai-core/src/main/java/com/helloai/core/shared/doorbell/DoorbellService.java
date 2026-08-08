package com.helloai.core.shared.doorbell;

import com.helloai.common.base.BizException;
import com.helloai.common.config.DoorbellProperties;
import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 门铃服务（AgentHub V3 门铃内核 PR-1）。
 *
 * <p><b>状态注记（2026-08-07）</b>：门铃通道已搁置。技术瓶颈——外部 AI Agent
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
 * <p>PR-1 只做连接内核，暂无响铃来源（响铃接线在 PR-2 由 InboxMessageCreatedEvent 驱动）。</p>
 *
 * <p>PR-3 收口值班鉴权：{@link #connect(Long)} 前置校验 {@code isOnDuty}（先打卡再接电话）；
 * checkOut / 租约到期时由 {@code DoorbellDutyListener} 监听 {@code DutyLeaseClosedEvent} 主动 {@link #disconnect(Long)}。</p>
 *
 * <p>PR-4 保活帧调度：{@code DoorbellKeepaliveTask} 周期调用 {@link #broadcastKeepalive()}，
 * 向本进程所有活跃连接发一帧 keep-alive，穿透反代空闲超时。</p>
 *
 * <p>PR-4 双心跳（可选，默认关）：若 {@code helloai.doorbell.refresh-heartbeat=true}，
 * {@link #connect(Long)} 建连时顺带调一次 {@link HeartbeatService#seen(Long)} 刷 {@code last_seen_at}，
 * keepalive 轮不刷（避免僵尸连接掩盖真实离线）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoorbellService {

    private final DoorbellProperties properties;
    private final DoorbellRegistry registry;
    private final AgentDutyLeaseService dutyLeaseService;
    private final HeartbeatService heartbeatService;

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
    public SseEmitter connect(Long agentId) {
        if (!properties.isEnabled()) {
            throw new BizException("门铃通道未启用（helloai.doorbell.enabled=false）");
        }
        // 先打卡再接电话：未持有 ACTIVE 值班租约的 Agent 不允许建门铃连接（设计 §6.1）。
        if (!dutyLeaseService.isOnDuty(agentId)) {
            throw new BizException("Agent 未在岗（无 ACTIVE 值班租约），请先 checkIn 再建立门铃连接");
        }
        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeoutMs());
        emitter.onCompletion(() -> registry.unregister(agentId, emitter));
        emitter.onTimeout(() -> {
            registry.unregister(agentId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> registry.unregister(agentId, emitter));

        registry.register(agentId, emitter);
        doSend(agentId, emitter, DoorbellSignal.connected());
        // 双心跳（可选，默认关）：建连是客户端主动、最可信的存活证据，顺带刷一次 last_seen_at。
        if (properties.isRefreshHeartbeat()) {
            refreshSeen(agentId);
        }
        log.info("门铃已建连: agentId={}, activeConnections={}", agentId, registry.size());
        return emitter;
    }

    /**
     * 向指定 Agent 响铃（尽力而为）。
     *
     * @return true=已成功送达门铃；false=Agent 未连门铃或发送失败（已静默降级，靠轮询兜底）
     */
    public boolean ring(Long agentId, DoorbellSignal signal) {
        SseEmitter emitter = registry.get(agentId);
        if (emitter == null) {
            // Agent 未连门铃（未在岗 / 断连），静默跳过，由 pullTasks 轮询兜底
            return false;
        }
        return doSend(agentId, emitter, signal);
    }

    /**
     * 主动断开某 Agent 的门铃连接（如 checkOut / 租约到期时调用）。
     */
    public void disconnect(Long agentId) {
        SseEmitter emitter = registry.get(agentId);
        if (emitter == null) {
            return;
        }
        registry.unregister(agentId, emitter);
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 连接可能已断，忽略
        }
        log.info("门铃已主动断开: agentId={}", agentId);
    }

    /** 当前门铃连接总数。 */
    public int connectionCount() {
        return registry.size();
    }

    /**
     * 双心跳：建连时顺带刷新 Agent 心跳（PR-4，默认关）。
     *
     * <p>尽力而为：{@link HeartbeatService#seen(Long)} 自带事务，失败只记 warn，
     * 绝不阻断门铃建连（心跳只是附带优化，不是建连前置条件）。</p>
     */
    private void refreshSeen(Long agentId) {
        try {
            heartbeatService.seen(agentId);
        } catch (Exception e) {
            log.warn("门铃建连刷新心跳失败（不影响建连）: agentId={}, err={}", agentId, e.toString());
        }
    }

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
     * <p>尽力而为：单条连接发送失败由 {@link #doSend} 静默注销（等待客户端重连），
     * 不影响同轮其它连接。</p>
     *
     * @return 本轮成功送达保活帧的连接数
     */
    public int broadcastKeepalive() {
        int[] sent = {0};
        registry.forEach((agentId, emitter) -> {
            if (doSend(agentId, emitter, DoorbellSignal.keepalive())) {
                sent[0]++;
            }
        });
        return sent[0];
    }

    /**
     * 实际发送一帧信号；失败即注销连接并静默降级，绝不向上抛异常。
     */
    private boolean doSend(Long agentId, SseEmitter emitter, DoorbellSignal signal) {
        try {
            emitter.send(SseEmitter.event()
                    .name(signal.getType())
                    .data(signal, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            log.debug("门铃响铃失败，注销连接: agentId={}, type={}, err={}",
                    agentId, signal.getType(), e.toString());
            registry.unregister(agentId, emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignore) {
                // 连接已不可用，忽略二次异常
            }
            return false;
        }
    }
}
