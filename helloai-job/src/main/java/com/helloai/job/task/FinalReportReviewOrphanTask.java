package com.helloai.job.task;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.review.support.FinalReportReviewLock;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 最终报告审查孤儿兜底巡检（§12.2 审查链三级容错 L3）。
 *
 * <p><b>补的是什么洞</b>：报告审查的触发链此前只有 L1（AFTER_COMMIT 内存事件 + 本地线程池），
 * 触发指令完全活在进程内存里。JVM 重启、线程池任务丢失、或事件在提交后恰好落空，
 * 报告就永久停在 {@code REVIEWING}——既没有重投入口，也没有告警，只能人工介入
 * （代码注释自认「需人工介入」；生产日志里也确有一次 generated 事件无任何配对 review 事件的实证）。
 * 本任务以 <b>DB 状态为事实源</b>做定期扫描，是全链最后一道防线。</p>
 *
 * <p><b>为什么扫描只读 {@code final_report_time} 而不引入新列/新表</b>：
 * {@code final_report_time} 本身就是"这份报告何时写回"的权威时间戳，且每次重新生成/
 * 回滚都会刷新它，天然是"这一版报告"的版本标识。以它为超时基准既准确又零 schema 成本。</p>
 *
 * <p><b>处置策略：收敛 DONE（不重投审查）</b>。理由：</p>
 * <ul>
 *   <li>报告正文此时早已落库并交付（UI 可读、交付物 zip 含 {@code 01-最终整合报告.md}），
 *       审查只是增值质量闭环，不是交付门槛——这与报告链「只增不减」的容错哲学一致；</li>
 *   <li>重投会在审查持续失败（如 Reviewer Agent 长期离线）时每轮反复烧 LLM，无界；
 *       收敛 DONE 是确定性、零 token、无循环的止血；</li>
 *   <li>需要补跑审查时，UI「重新生成」按钮可人工触发（会走完整生成 + 审查链）。</li>
 * </ul>
 *
 * <p><b>保护机制</b>（与 {@link PlanningTimeoutTask} 同构）：</p>
 * <ul>
 *   <li><b>幂等</b>：收敛走 {@code TaskService.convergeFinalReportToDone} 的双条件 CAS
 *       （{@code final_report_status=REVIEWING} 且 {@code final_report_time} 未变），
 *       与 L1/L2 的收敛天然互斥——慢审查迟到收敛时 CAS 失败即跳过，不会覆盖新链状态</li>
 *   <li><b>防双审锁互斥（§12.5 #3）</b>：收敛前先抢与 L1/L2 审查体同款的
 *       {@code final_report_review:{taskId}} 锁——锁被持有说明审查正在进行（非真孤儿），跳过不收敛，
 *       避免慢审查（持锁可达 600s）被误判孤儿后与迟到 reject 互相覆盖。配套将巡检阈值提到 ≥ 锁 TTL</li>
 *   <li><b>ShedLock 实例级互斥</b>（{@code @SchedulerLock}，Redis 存储锁记录）保证多实例单轮仅一实例执行</li>
 *   <li><b>批量上限</b>防止单轮扫描过多阻塞调度</li>
 *   <li><b>逐条隔离</b>：单条失败只记日志不抛异常，不阻塞同轮其它记录</li>
 *   <li><b>开关跟随</b>：审查总开关关闭时不扫描（此时写回直接是 DONE，本就不该有 REVIEWING）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalReportReviewOrphanTask {

    private final TaskService taskService;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties dispatchProperties;
    /** §12.5 #3 防双审锁客户端：与 L1/L2 审查体共用同一把 {@code final_report_review:{taskId}} 锁。 */
    private final RedissonClient redissonClient;

    @Scheduled(fixedDelayString = "${helloai.dispatch.final-report-review-orphan-scan-interval-ms:30000}")
    @SchedulerLock(name = "finalReportReviewOrphan", lockAtMostFor = "PT60S")
    public void scan() {
        if (!dispatchProperties.isAutoFinalReportReviewEnabled()) {
            return;
        }
        try {
            int thresholdSeconds = dispatchProperties.getFinalReportReviewOrphanThresholdSeconds();
            int batchSize = dispatchProperties.getFinalReportReviewOrphanBatchSize();
            List<Task> orphans = taskService.listFinalReportReviewOrphans(thresholdSeconds, batchSize);
            if (orphans.isEmpty()) {
                return;
            }
            log.warn("最终报告 REVIEWING 孤儿巡检: 发现 {} 条超时未收敛（threshold={}s），审查触发链疑似丢失",
                    orphans.size(), thresholdSeconds);

            int converged = 0;
            int skipped = 0;
            int failed = 0;
            for (Task task : orphans) {
                try {
                    if (converge(task, thresholdSeconds)) {
                        converged++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("最终报告 REVIEWING 孤儿收敛失败: taskId={}", task.getId(), e);
                }
            }
            log.info("最终报告 REVIEWING 孤儿巡检完成: 扫描={}, 收敛={}, 跳过（版本已变）={}, 失败={}",
                    orphans.size(), converged, skipped, failed);
        } catch (Exception e) {
            log.error("FinalReportReviewOrphanTask 执行异常", e);
        }
    }

    /**
     * 单条收敛：CAS 置 DONE + 落 timeline 审计事件。
     *
     * @return true=已收敛；false=版本已变/已被兄弟链路收敛（CAS 未命中）
     */
    private boolean converge(Task task, int thresholdSeconds) {
        if (task.getFinalReportTime() == null) {
            log.debug("最终报告 REVIEWING 孤儿缺少 final_report_time，跳过: taskId={}", task.getId());
            return false;
        }
        // §12.5 #3：抢占与 L1/L2 审查体同款的防双审锁——锁被持有 = 审查正在进行（非真孤儿），
        // 跳过不收敛，避免慢审查（持锁可达 600s）被误判孤儿收敛 DONE 后，迟到的 reject 又把它
        // 翻回 GENERATING 覆盖。waitTime=0 保持「抢不到即跳过」；leaseTime 与审查体同源（TTL 兜底崩溃残留）。
        RLock lock = redissonClient.getLock(FinalReportReviewLock.key(task.getId()));
        boolean locked = false;
        try {
            locked = lock.tryLock(0, FinalReportReviewLock.TTL_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("最终报告 REVIEWING 孤儿跳过：防双审锁被持有，审查正在进行中: taskId={}", task.getId());
                return false;
            }
            boolean done = taskService.convergeFinalReportToDone(task.getId(), task.getFinalReportTime());
            if (!done) {
                log.debug("最终报告 REVIEWING 孤儿收敛跳过（版本已变或已被收敛）: taskId={}", task.getId());
                return false;
            }
            long stuckSeconds = Math.max(0,
                    ChronoUnit.SECONDS.between(task.getFinalReportTime(), OffsetDateTime.now()));
            taskTimelineService.recordEvent(task.getId(), null,
                    "task_final_report_review_orphan_converged", AgentRole.SYSTEM, null,
                    Map.of("thresholdSeconds", thresholdSeconds,
                            "stuckSeconds", stuckSeconds,
                            "reportTime", task.getFinalReportTime().toString()));
            log.warn("最终报告审查链丢失，已兜底收敛 DONE: taskId={}, 卡住 {}s（阈值 {}s）",
                    task.getId(), stuckSeconds, thresholdSeconds);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("最终报告 REVIEWING 孤儿收敛获取防双审锁被中断: taskId={}", task.getId());
            return false;
        } finally {
            // isHeldByCurrentThread 防御：锁已过期被他人接管时，本线程不得释放他人持有的锁
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
