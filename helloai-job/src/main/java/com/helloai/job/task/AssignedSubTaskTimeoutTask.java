package com.helloai.job.task;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * ASSIGNED 超时未 claim 巡检任务。
 *
 * <p>扫描 status=ASSIGNED 且 update_time 早于阈值的子任务，
 * 将它们回收到 PENDING 并重新进入调度链。填补 """ASSIGNED → 长时间无人 claim → 永远卡死""
 * 的可靠性缺口。</p>
 *
 * <p>保护机制：
 * <ul>
 *   <li>只处理 ASSIGNED，不碰 IN_PROGRESS（由 {@link SubTaskTimeoutTask} 负责）</li>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证多实例安全</li>
 *   <li>batch limit 防止单轮扫描过多阻塞调度</li>
 *   <li>每条失败只记日志不抛异常，不阻塞同轮其它记录</li>
 * </ul>
 * </p>
 *
 * @see SubTaskDispatchService#redispatchAssignedTimeout
 * @see SubTaskTimeoutTask
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignedSubTaskTimeoutTask {

    private final SubTaskMapper subTaskMapper;
    private final SubTaskDispatchService subTaskDispatchService;
    private final AgentService agentService;
    private final AgentDispatchProperties agentDispatchProperties;

    /** 单次扫描最多处理的子任务数 */
    private static final int BATCH_LIMIT = 50;

    @Scheduled(fixedRate = 30_000)
    @SchedulerLock(name = "assignedSubTaskTimeout", lockAtMostFor = "PT60S")
    public void scan() {
        try {
            OffsetDateTime deadline = OffsetDateTime.now()
                    .minusMinutes(agentDispatchProperties.getAssignedTimeoutMinutes());
            List<SubTask> timedOut = subTaskMapper.selectTimedOutAssigned(deadline, BATCH_LIMIT);

            if (timedOut.isEmpty()) {
                return;
            }

            log.info("ASSIGNED超时巡检: 发现 {} 个超时未claim子任务", timedOut.size());

            int recovered = 0;
            int failed = 0;
            for (SubTask subTask : timedOut) {
                try {
                    Long originalAgentId = subTask.getAssignedAgentId();
                    Agent originalAgent = originalAgentId != null
                            ? agentService.getById(originalAgentId) : null;
                    AgentRole role = originalAgent != null && originalAgent.getRole() != null
                            ? originalAgent.getRole() : AgentRole.EXECUTOR;

                    subTaskDispatchService.redispatchAssignedTimeout(
                            subTask.getId(), originalAgentId, role);
                    recovered++;
                    log.info("ASSIGNED超时已回收: subTaskId={}, originalAgentId={}",
                            subTask.getId(), originalAgentId);
                } catch (Exception e) {
                    failed++;
                    log.error("ASSIGNED超时回收失败: subTaskId={}", subTask.getId(), e);
                }
            }

            log.info("ASSIGNED超时巡检完成: 扫描={}, 回收={}, 失败={}",
                    timedOut.size(), recovered, failed);

        } catch (Exception e) {
            log.error("AssignedSubTaskTimeoutTask 执行异常", e);
        }
    }

}
