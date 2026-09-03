package com.helloai.job.task;

import com.helloai.core.agent.event.EventReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件流对账任务（Phase 0 B3）。
 *
 * <p>周期扫描最近变更的子任务，校验事件流末条事件与业务状态是否一致
 * （ADR-001 §5.3：事件是业务状态的投影，事件流终态事件应与业务表状态匹配）。
 * 不一致仅告警日志（事件 write-only，不做任何业务修正），供人工排查埋点缺口。</p>
 *
 * <p>保护机制（与 {@link LeaseReconcilerTask} 同构）：
 * <ul>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证同一时刻只有一台节点执行对账</li>
 *   <li>batch limit 防止单轮扫描过多阻塞（默认 500）</li>
 *   <li>业务异常不抛出：整轮失败只记 error，不影响下一轮调度</li>
 * </ul>
 *
 * @see EventReconciliationService#reconcile
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventReconciliationTask {

    private final EventReconciliationService eventReconciliationService;

    /** 单轮候选子任务数量上限（窗口内变更量超限时本轮截断，下一轮继续）。 */
    private static final int BATCH_LIMIT = 500;

    /**
     * 60 秒一轮对账：与事件埋点事务窗口（秒级）相比足够宽松，
     * 对账窗口（10 分钟）内所有活跃子任务均会被覆盖。
     */
    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "agentEventReconcile", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            int mismatches = eventReconciliationService.reconcile(BATCH_LIMIT);
            if (mismatches > 0) {
                log.warn("事件对账发现不一致: 数量={}（详见上方逐条 warn，事件 write-only 仅告警不修正）", mismatches);
            }
        } catch (Exception e) {
            log.error("EventReconciliationTask 执行异常", e);
        }
    }
}