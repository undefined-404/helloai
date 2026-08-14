package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionCompensationTask {

    private final AgentExecutionRecordMapper executionRecordMapper;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final ExecutionResultHandler executionResultHandler;
    private final SubTaskService subTaskService;
    private final AgentExecutionProperties executionProperties;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redis;
    private final ExternalAgentFailureTracker failureTracker;
    private final AgentMapper agentMapper;

    private static final String LOCK_KEY = "scheduler:lock:ExecutionComp";

    @Scheduled(fixedRate = 30000)
    public void compensate() {
        if (!tryLock()) return;

        try {
            OffsetDateTime now = OffsetDateTime.now();
            Duration pendingTimeout = Duration.ofMinutes(executionProperties.getPendingTimeoutMinutes());
            Duration runningTimeout = Duration.ofMinutes(executionProperties.getRunningTimeoutMinutes());

            List<AgentExecutionRecord> pendingStuck = executionRecordMapper
                    .selectByStatusAndCreateTimeBefore(ExecutionStatus.PENDING, now.minus(pendingTimeout));

            for (AgentExecutionRecord record : pendingStuck) {
                log.warn("Execution PENDING timeout: eventId={}, subTaskId={}",
                        record.getEventId(), record.getSubTaskId());
                compensateRecordAtomically(
                        record,
                        "PENDING timeout: ACK lost or JVM crash before execution");
            }

            List<AgentExecutionRecord> runningStuck = executionRecordMapper
                    .selectByStatusAndStartTimeBefore(ExecutionStatus.RUNNING, now.minus(runningTimeout));

            for (AgentExecutionRecord record : runningStuck) {
                log.error("Execution RUNNING timeout: eventId={}, subTaskId={}",
                        record.getEventId(), record.getSubTaskId());
                compensateRecordAtomically(
                        record,
                        "RUNNING timeout: execution exceeded "
                                + executionProperties.getRunningTimeoutMinutes() + " minutes");
            }

            if (!pendingStuck.isEmpty() || !runningStuck.isEmpty()) {
                log.info("执行记录补偿: PENDING超时={}, RUNNING超时={}",
                        pendingStuck.size(), runningStuck.size());
            }

        } finally {
            unlock();
        }
    }

    private void compensateRecordAtomically(AgentExecutionRecord record, String errorMessage) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!agentExecutionRecordService.markTimeout(record.getId())) {
                    return;
                }

                SubTask subTask = subTaskService.getById(record.getSubTaskId());
                if (subTask != null && subTask.getStatus() == SubTaskStatus.IN_PROGRESS) {
                    executionResultHandler.handleFailure(
                            record.getSubTaskId(),
                            null,
                            new BizException(errorMessage));
                }
            });
        } catch (Exception e) {
            log.error("执行记录超时补偿失败: recordId={}, subTaskId={}",
                    record.getId(), record.getSubTaskId(), e);
        }

        // N11 阈值回退计数：超时被视为执行失败。仅对 CLI_CLIENT Agent 累加；
        // SQL 条件已限定 access_type=CLI_CLIENT，误调不会写库。
        Long agentId = record.getAgentId();
        if (agentId != null) {
            failureTracker.recordFailure(agentId);
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
