package com.helloai.job.task;

import com.helloai.core.planner.service.PlanAttemptDispatcher;
import com.helloai.core.planner.service.PlanAttemptDispatcher.DispatchReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拆解尝试派发兜底（`TD-16b`）。
 *
 * <p><b>它兜的到底是什么</b>：{@code planById} 已经把「我要拆」的意图和
 * {@code task.status = PLANNING} 写进同一个短事务，随后在事务外立刻 {@code @Async} 执行。
 * 快路径在正常情况下延迟为零；本任务只处理快路径没跑起来的那部分——
 * 进程在 T1 提交后崩、线程池拒绝、执行段跑一半失联。</p>
 *
 * <p><b>与 {@link PlanningTimeoutTask} 必须在同一周期跑、但租约必须更短</b>：
 * 两者都是 30s。<code>helloai.planner.attempt-lease-minutes</code>（默认 7）必须**小于**
 * <code>planning-timeout-minutes</code>（默认 10）——否则任务级巡检先动手把 {@code task} 回退
 * {@code PENDING}，派发器就永远轮不到自愈，这条兜底形同虚设。
 * 配置项默认值即按此约束给定（7 &lt; 10），改动任一值都要重新检查这个大小关系。</p>
 *
 * <p>ShedLock 实例级互斥，与 {@code PlanningTimeoutTask} / {@code AssignedSubTaskTimeoutTask} 同构。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanAttemptDispatchTask {

    private final PlanAttemptDispatcher planAttemptDispatcher;

    /** 单轮每类扫描上限（防单轮阻塞调度）。 */
    private static final int BATCH_LIMIT = 50;

    @Scheduled(fixedRate = 30_000)
    @SchedulerLock(name = "planAttemptDispatch", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            DispatchReport report = planAttemptDispatcher.dispatchOnce(BATCH_LIMIT);
            if (!report.isEmpty()) {
                log.info("拆解尝试派发轮次完成: 回收悬挂={}, 重派={}, 终止={}, 失败={}",
                        report.reclaimedHanging(), report.redispatched(),
                        report.abandoned(), report.failed());
            }
        } catch (Exception e) {
            // 单轮失败不抛出：定时任务抛异常会污染下一轮调度日志，
            // 且下一轮本来就会重扫（与 PlanningTimeoutTask 的口径一致）。
            log.error("拆解尝试派发轮次失败: {}", e.getMessage(), e);
        }
    }
}
