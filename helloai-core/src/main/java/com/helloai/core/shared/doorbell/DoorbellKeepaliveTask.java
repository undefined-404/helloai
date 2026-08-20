package com.helloai.core.shared.doorbell;

import com.helloai.common.config.DoorbellProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 门铃保活帧调度任务（AgentHub 门铃 PR-4）。
 *
 * <p>周期性向本进程 {@link DoorbellRegistry} 中所有活跃 {@code SseEmitter} 广播一帧
 * {@code keepalive}，穿透 Nginx / 反代的空闲连接超时（通常 60s 无数据即断），
 * 让门铃长连接不被中间层提前切断（设计 {@code doc/HelloAI_门铃通知通道设计.md} §6.2）。</p>
 *
 * <p><b>本地无锁、每实例都跑</b>：这与 {@code DutyLeaseExpirationTask} 的 Redis 选主锁
 * 模式<b>相反</b>。门铃连接是进程内状态，某个 Agent 的连接只落在持有它的那个实例上，
 * 因此每个实例都必须保活自己 {@link DoorbellRegistry} 里的连接；若像到期巡检那样选主
 * 只让一台节点执行，其他实例的连接就得不到保活而被反代掐断。遍历发送发生在内存 Map 上、
 * 量级为在岗外部 Agent 数，开销可控，无需分布式协调。</p>
 *
 * <p>调度间隔取 {@code helloai.doorbell.keepalive-interval-ms}（默认 15s）；门铃通道关闭
 * （{@code helloai.doorbell.enabled=false}）或当前无连接时直接跳过，避免空转。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoorbellKeepaliveTask {

    private final DoorbellProperties properties;
    private final DoorbellService doorbellService;

    /**
     * 按 {@code helloai.doorbell.keepalive-interval-ms}（默认 15s）向所有活跃连接发保活帧。
     *
     * <p>整体 try-catch 兜底：保活是纯增强，任何异常都不得冒泡打断调度线程，
     * 失败连接的清理由 {@link DoorbellService#broadcastKeepalive} 内部静默完成。</p>
     */
    @Scheduled(fixedRateString = "${helloai.doorbell.keepalive-interval-ms:15000}")
    public void keepalive() {
        if (!properties.isEnabled()) {
            return;
        }
        if (doorbellService.connectionCount() == 0) {
            return;
        }
        try {
            int sent = doorbellService.broadcastKeepalive();
            log.debug("门铃保活帧已广播: sent={}, remaining={}", sent, doorbellService.connectionCount());
        } catch (Exception e) {
            log.warn("门铃保活广播异常（已忽略，靠客户端重连与轮询兜底）", e);
        }
    }
}
