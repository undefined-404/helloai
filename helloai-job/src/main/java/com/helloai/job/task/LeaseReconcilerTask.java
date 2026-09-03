package com.helloai.job.task;

import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 子任务执行租约过期回收任务（Phase 0 A2.4）。
 *
 * <p>周期扫描 {@code sub_task} 中 {@code status=IN_PROGRESS AND owner IS NOT NULL}
 * 且 {@code lease_until} 已过期的行，CAS 回收为 PENDING 并清空
 * assignedAgentId / owner / leaseUntil，交回既有分发链重新派发。
 * 处理 Worker 崩溃 / 宕机后无人续租的任务，是 Watchdog 机制的兜底闭环。</p>
 *
 * <p>保护机制（与 {@link DutyLeaseExpirationTask} 同构）：
 * <ul>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证同一时刻只有一台节点执行扫描</li>
 *   <li>batch limit 防止单轮扫描过多阻塞（默认 200）</li>
 *   <li>业务异常不抛出：单条失败只记 warn（含与 Watchdog 续期的 CAS 竞争失败），不影响同轮其它记录</li>
 * </ul>
 *
 * @see SubTaskService#reclaimExpiredLeases
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseReconcilerTask {

    private final SubTaskService subTaskService;

    /** 单轮回收上限（同一轮最多回收多少条）。 */
    private static final int BATCH_LIMIT = 200;

    /**
     * 30 秒一轮扫描：粒度与 Watchdog 续租周期（150s）匹配，
     * 保证 Worker 崩溃后租约回收延迟不超过 ~30s + TTL 余量。
     */
    @Scheduled(fixedRate = 30_000)
    @SchedulerLock(name = "subTaskLeaseReconcile", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            int reclaimed = subTaskService.reclaimExpiredLeases(BATCH_LIMIT);
            if (reclaimed > 0) {
                log.info("租约过期回收完成: 回收行数={}", reclaimed);
            }
        } catch (Exception e) {
            log.error("LeaseReconcilerTask 执行异常", e);
        }
    }
}