package com.helloai.job.task;

import com.helloai.core.task.service.TaskLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务生命周期对账兜底（`ADR-008` §5 的第 23 个定时任务）。
 *
 * <p><b>它补的是什么洞</b>：任务收尾（`tryCloseTask`）与派生完成态传播是在
 * 提交后异步线程里做的普通方法调用，进程崩在「CAS 改库」与「改父 SubTask」之间时，
 * 库里会留下「子任务全终态但任务非终态」「派生任务已完成但父 SubTask 仍在等派生」
 * 这类永久悬挂——此前 22 个定时任务里没有任何一个扫这个口径。报告缺失同理：
 * 自动生成是 `@Async + catch{log.warn}`，失败即无人重试。</p>
 *
 * <p><b>三项对账全部幂等</b>（判据在 {@link TaskLifecycleService}，本任务只负责调度）：</p>
 * <ol>
 *   <li>非终态任务但有效子任务全终态 → 重放收尾（CAS 保证只生效一次）</li>
 *   <li>派生 Task 终态但父 SubTask 仍 `AWAITING_DERIVED` → 重放传播（状态机保证只生效一次）</li>
 *   <li>`DONE` 但整合报告缺失 → 触发补生成（服务自身 CAS 防重入；`FAILED` 不在扫描范围，避免每轮烧额度）</li>
 * </ol>
 *
 * <p><b>ShedLock 实例级互斥</b>，与其他 22 个 `@Scheduled` 同构；轮询周期取 30s，
 * 与 `PlanAttemptDispatchTask` / `FinalReportReviewOrphanTask` 一致。单轮失败不抛出——
 * 下一轮本来就会重扫。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLifecycleReconcileTask {

    private final TaskLifecycleService taskLifecycleService;

    /** 单轮每类扫描上限（防单轮阻塞调度）。 */
    private static final int BATCH_LIMIT = 50;

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "taskLifecycleReconcile", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            int closed = taskLifecycleService.reconcileSettledTasks(BATCH_LIMIT);
            int propagated = taskLifecycleService.reconcileDerivations(BATCH_LIMIT);
            int reports = taskLifecycleService.reconcileMissingFinalReports(BATCH_LIMIT);
            if (closed > 0 || propagated > 0 || reports > 0) {
                log.info("生命周期对账完成: 收尾={}, 派生传播={}, 报告补生成={}", closed, propagated, reports);
            }
        } catch (Exception e) {
            log.error("任务生命周期对账轮次失败: {}", e.getMessage(), e);
        }
    }
}
