package com.helloai.job.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskTimeoutTask {

    private final SubTaskService subTaskService;

    private static final long IN_PROGRESS_TIMEOUT_HOURS = 2;

    @Scheduled(fixedRate = 30000)
    @SchedulerLock(name = "subTaskTimeout", lockAtMostFor = "PT60S")
    public void checkTimeout() {
        OffsetDateTime deadline = OffsetDateTime.now().minusHours(IN_PROGRESS_TIMEOUT_HOURS);

        List<SubTask> timedOut = subTaskService.list(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getStatus, SubTaskStatus.IN_PROGRESS)
                .le(SubTask::getUpdateTime, deadline)
                .eq(SubTask::getDeleted, 0));

        for (SubTask subTask : timedOut) {
            log.warn("子任务超时: subTaskId={}, lastUpdate={}", subTask.getId(), subTask.getUpdateTime());
            try {
                subTaskService.block(subTask.getId());
                log.info("超时子任务已 BLOCKED: subTaskId={}", subTask.getId());
            } catch (Exception e) {
                log.error("标记 BLOCKED 失败: subTaskId={}", subTask.getId(), e);
            }
        }

        if (!timedOut.isEmpty()) {
            log.info("超时巡检: 发现 {} 个超时子任务", timedOut.size());
        }
    }
}
