package com.helloai.job.task;

import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * PLANNING 超时卡死巡检任务（拆解异步化改造兜底）。
 *
 * <p>拆解改为"提交即返回 + 异步执行"后，若异步线程丢失（JVM 重启/异常退出），
 * 任务会永久卡在 PLANNING 且无草案产出。本任务扫描 status=PLANNING 且 update_time
 * 早于 {@code planning-timeout-minutes} 阈值的任务，CAS 回退 PENDING 并记录
 * {@code task_plan_timeout_recovered} timeline，用户可重新触发拆解。</p>
 *
 * <p>保护机制（与 {@link AssignedSubTaskTimeoutTask} 同构）：
 * <ul>
 *   <li>幂等设计：回退走 CAS（仅 status=PLANNING 时置 PENDING），与异步成功路径
 *       天然互斥——慢线程迟到完成拆解或用户已确认时，CAS 失败即跳过</li>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证多实例安全</li>
 *   <li>batch limit 防止单轮扫描过多阻塞调度</li>
 *   <li>每条失败只记日志不抛异常，不阻塞同轮其它记录</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanningTimeoutTask {

    private final TaskMapper taskMapper;
    private final TaskService taskService;
    private final TaskTimelineService taskTimelineService;
    private final PlannerDecomposeProperties plannerDecomposeProperties;

    /** 单次扫描最多处理的任务数 */
    private static final int BATCH_LIMIT = 50;

    @Scheduled(fixedRate = 30_000)
    @SchedulerLock(name = "planningTimeout", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            OffsetDateTime deadline = OffsetDateTime.now()
                    .minusMinutes(plannerDecomposeProperties.getPlanningTimeoutMinutes());
            List<Task> timedOut = taskMapper.selectTimedOutPlanning(deadline, BATCH_LIMIT);

            if (timedOut.isEmpty()) {
                return;
            }

            log.info("PLANNING超时巡检: 发现 {} 个超时卡死任务", timedOut.size());

            int recovered = 0;
            int failed = 0;
            for (Task task : timedOut) {
                try {
                    if (recover(task)) {
                        recovered++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("PLANNING超时回收失败: taskId={}", task.getId(), e);
                }
            }

            log.info("PLANNING超时巡检完成: 扫描={}, 回收={}, 失败={}",
                    timedOut.size(), recovered, failed);

        } catch (Exception e) {
            log.error("PlanningTimeoutTask 执行异常", e);
        }
    }

    /**
     * 单条回收：CAS 回退 PENDING + 记录 timeline。
     *
     * @return true=已回收；false=状态已变化（异步段迟到完成/用户已确认）跳过
     */
    private boolean recover(Task task) {
        boolean cas = taskService.lambdaUpdate()
                .eq(Task::getId, task.getId())
                .eq(Task::getStatus, TaskStatus.PLANNING)
                .set(Task::getStatus, TaskStatus.PENDING)
                .update();
        if (!cas) {
            log.info("PLANNING超时回收跳过（状态已变化）: taskId={}", task.getId());
            return false;
        }
        taskTimelineService.recordEvent(task.getId(), null, "task_plan_timeout_recovered",
                AgentRole.PLANNER, null,
                Map.of("planningTimeoutMinutes", plannerDecomposeProperties.getPlanningTimeoutMinutes()));
        log.info("PLANNING超时已回退 PENDING: taskId={}", task.getId());
        return true;
    }
}
