package com.helloai.job.task;

import com.helloai.common.config.WatchdogProperties;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 子任务执行租约看门狗续期任务（Phase 0 A2.3）。
 *
 * <p>周期为当前节点持有的全部 {@code sub_task} 执行租约续期（{@code owner = 本节点名}），
 * 防止正常执行中的长任务因租约到期被 {@link LeaseReconcilerTask} 误回收。</p>
 *
 * <p><b>ShedLock 豁免</b>（规范 §21.1/§22）：本任务必须<b>每节点独立运行</b>——
 * 只续自己 {@code owner} 的租约，集群单例会致其他节点正在执行的任务无人续租而被误回收，
 * 故<b>不加 {@code @SchedulerLock}</b>。与 {@link LeaseReconcilerTask}（集群单例）相反。</p>
 *
 * <p>运行节奏：固定 {@code watchdog.renew-interval-seconds}（默认 150s，为 TTL 的一半）
 * 续期至 {@code now + watchdog.ttl-seconds}（默认 300s）。</p>
 *
 * @see SubTaskService#renewCurrentNodeLeases
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "helloai.agent.watchdog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WatchdogLeaseRenewTask {

    private final SubTaskService subTaskService;
    private final WatchdogProperties watchdogProperties;

    /** 单轮续期上限（同一轮最多续多少条，防止单轮过长阻塞调度线程）。 */
    private static final int BATCH_LIMIT = 200;

    /**
     * 续租周期：默认 150 秒（TTL 300s 的一半）；无 ShedLock，每节点独立执行。
     */
    @Scheduled(fixedRateString = "${helloai.agent.watchdog.renew-interval-seconds:150}000")
    public void renew() {
        try {
            int renewed = subTaskService.renewCurrentNodeLeases(
                    OffsetDateTime.now().plusSeconds(watchdogProperties.getTtlSeconds()), BATCH_LIMIT);
            if (renewed > 0) {
                log.info("看门狗续期完成: 续期条数={}", renewed);
            }
        } catch (Exception e) {
            // 业务异常不抛出：续期失败留给 Reconciler 兜底回收，避免周期任务中断
            log.error("WatchdogLeaseRenewTask 执行异常", e);
        }
    }
}