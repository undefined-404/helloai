package com.helloai.job.task;

import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.mapper.AgentExecutionRecordMapper;
import com.helloai.core.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionCompensationTask {

    private final AgentExecutionRecordMapper executionRecordMapper;
    private final SubTaskService subTaskService;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:ExecutionComp";
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration RUNNING_TIMEOUT = Duration.ofMinutes(10);

    @Scheduled(fixedRate = 30000)
    public void compensate() {
        if (!tryLock()) return;

        try {
            OffsetDateTime now = OffsetDateTime.now();

            List<AgentExecutionRecord> pendingStuck = executionRecordMapper
                    .selectByStatusAndCreateTimeBefore(ExecutionStatus.PENDING, now.minus(PENDING_TIMEOUT));

            for (AgentExecutionRecord record : pendingStuck) {
                log.warn("Execution PENDING timeout, BLOCKED: eventId={}, subTaskId={}",
                        record.getEventId(), record.getSubTaskId());

                executionRecordMapper.updateStatus(record.getId(), ExecutionStatus.FAILED,
                        "PENDING timeout: ACK lost or JVM crash before execution");

                try {
                    subTaskService.changeStatus(record.getSubTaskId(), SubTaskStatus.BLOCKED, null);
                } catch (Exception e) {
                    log.error("触发 BLOCKED 失败: subTaskId={}", record.getSubTaskId(), e);
                }
            }

            List<AgentExecutionRecord> runningStuck = executionRecordMapper
                    .selectByStatusAndStartTimeBefore(ExecutionStatus.RUNNING, now.minus(RUNNING_TIMEOUT));

            for (AgentExecutionRecord record : runningStuck) {
                log.error("Execution RUNNING timeout: eventId={}, subTaskId={}",
                        record.getEventId(), record.getSubTaskId());

                executionRecordMapper.updateStatus(record.getId(), ExecutionStatus.TIMEOUT,
                        "RUNNING timeout: AI execution exceeded 10 minutes");

                try {
                    subTaskService.changeStatus(record.getSubTaskId(), SubTaskStatus.BLOCKED, null);
                } catch (Exception e) {
                    log.error("触发 BLOCKED 失败: subTaskId={}", record.getSubTaskId(), e);
                }
            }

            if (!pendingStuck.isEmpty() || !runningStuck.isEmpty()) {
                log.info("执行记录补偿: PENDING超时={}, RUNNING超时={}",
                        pendingStuck.size(), runningStuck.size());
            }

        } finally {
            unlock();
        }
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 60, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }
}
