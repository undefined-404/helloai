package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.agent.observability.FailureCategory;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionCompensationTask {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final ExecutionResultHandler executionResultHandler;
    private final SubTaskService subTaskService;
    private final AgentExecutionProperties executionProperties;
    private final TransactionTemplate transactionTemplate;
    private final ExternalAgentFailureTracker failureTracker;
    private final TaskTimelineService taskTimelineService;

    @Scheduled(fixedRate = 30000)
    @SchedulerLock(name = "executionCompensation", lockAtMostFor = "PT60S")
    public void compensate() {
        OffsetDateTime now = OffsetDateTime.now();
        Duration pendingTimeout = Duration.ofMinutes(executionProperties.getPendingTimeoutMinutes());
        Duration runningTimeout = Duration.ofMinutes(executionProperties.getRunningTimeoutMinutes());

        List<AgentExecutionRecord> pendingStuck = agentExecutionRecordService
                .listByStatusCreatedBefore(ExecutionStatus.PENDING, now.minus(pendingTimeout));

        for (AgentExecutionRecord record : pendingStuck) {
            log.warn("Execution PENDING timeout: eventId={}, subTaskId={}",
                    record.getEventId(), record.getSubTaskId());
            compensateRecordAtomically(
                    record,
                    "PENDING timeout: ACK lost or JVM crash before execution");
        }

        List<AgentExecutionRecord> runningStuck = agentExecutionRecordService
                .listByStatusStartedBefore(ExecutionStatus.RUNNING, now.minus(runningTimeout));

        // MA-10 改动 3：「无进展」扫描（取代「总时长」判定）。
        // 纪律（与 MA-4 第一段同源）：noProgressDetectionEnforced=false 时只观察——命中的记录
        // 只记日志，仍走上面的旧判据；置 true 后旧判据不再参与（本段接管）。
        List<AgentExecutionRecord> noProgressStuck = List.of();
        if (executionProperties.isNoProgressDetectionEnforced()) {
            noProgressStuck = agentExecutionRecordService.listRunningWithoutProgressBefore(
                    now.minus(Duration.ofMinutes(executionProperties.getNoProgressTimeoutMinutes())));
            runningStuck = List.of();
        } else {
            // 观察期：只统计，不处置。目的是先拿到「有哪些任务会被无进展判据命中」的真实分布，
            // 再决定阈值与是否接管（若直接接管，误杀会立刻变成 BLOCKED + 自动重派）。
            noProgressStuck = agentExecutionRecordService.listRunningWithoutProgressBefore(
                    now.minus(Duration.ofMinutes(executionProperties.getNoProgressTimeoutMinutes())));
            for (AgentExecutionRecord record : noProgressStuck) {
                log.warn("无进展观察（不处置）: subTaskId={}, startTime={}, lastProgressAt={}, 阈值={}分钟",
                        record.getSubTaskId(), record.getStartTime(), record.getLastProgressAt(),
                        executionProperties.getNoProgressTimeoutMinutes());
            }
            noProgressStuck = List.of();
        }

        for (AgentExecutionRecord record : runningStuck) {
            // G-006：外部路径的 RUNNING 行由「租约回收 / 2 小时超时 / 状态退出」收口，
            // 不走本补偿。原因：本任务的判定是「平台内执行命令超过 runningTimeoutMinutes」，
            // 而外部 Agent 的合法执行时长由租约与人工节奏决定；一旦在这里按 10 分钟判超时，
            // 会直接把仍在外部执行的子任务打成 BLOCKED（外部执行者毫不知情）。
            if (!isPlatformExecution(record)) {
                log.debug("跳过外部执行记录的 RUNNING 超时补偿: subTaskId={}, accessType={}",
                        record.getSubTaskId(), record.getAccessType());
                continue;
            }
            log.error("Execution RUNNING timeout: eventId={}, subTaskId={}",
                    record.getEventId(), record.getSubTaskId());
            compensateRecordAtomically(
                    record,
                    "RUNNING timeout: execution exceeded "
                            + executionProperties.getRunningTimeoutMinutes() + " minutes");
        }

        for (AgentExecutionRecord record : noProgressStuck) {
            if (!isPlatformExecution(record)) {
                log.debug("跳过外部执行记录的无进展补偿: subTaskId={}, accessType={}",
                        record.getSubTaskId(), record.getAccessType());
                continue;
            }
            // MA-9 复用点：等待人工审批期间「没有进展」是预期内的，不该判死。
            // 判定只读 sub_task.context.pendingApproval（不引入审批表依赖）。
            if (isAwaitingApproval(record)) {
                log.info("跳过审批等待中的执行记录（无进展豁免）: subTaskId={}", record.getSubTaskId());
                continue;
            }
            log.error("Execution no-progress timeout: eventId={}, subTaskId={}, lastProgressAt={}",
                    record.getEventId(), record.getSubTaskId(), record.getLastProgressAt());
            compensateRecordAtomically(
                    record,
                    "RUNNING no-progress timeout: no progress for "
                            + executionProperties.getNoProgressTimeoutMinutes() + " minutes");
        }

        if (!pendingStuck.isEmpty() || !runningStuck.isEmpty() || !noProgressStuck.isEmpty()) {
            log.info("执行记录补偿: PENDING超时={}, RUNNING超时={}, 无进展超时={}",
                    pendingStuck.size(), runningStuck.size(), noProgressStuck.size());
        }
    }

    /**
     * 该执行记录是否处于「等人工审批」状态（`MA-9` 复用点，`MA-10` 改动 3 的豁免分支）。
     *
     * <p>只读 {@code sub_task.context.pendingApproval} 的存在性——本任务不需要知道审批的业务模型
     * （谁批的、几档策略、有效期多久），那是 `MA-9` 的事。这条边界让两个项各自可独立回滚。</p>
     *
     * <p><b>读失败按「未等待」</b>：读不到 context 时给出豁免会凭空延长执行，
     * 比误判一次超时更危险（前者永不收敛）。</p>
     */
    private boolean isAwaitingApproval(AgentExecutionRecord record) {
        Long subTaskId = record.getSubTaskId();
        if (subTaskId == null) {
            return false;
        }
        try {
            SubTask subTask = subTaskService.getById(subTaskId);
            if (subTask == null || subTask.getContext() == null) {
                return false;
            }
            return subTask.getContext().get(SubTaskContextKeys.PENDING_APPROVAL) != null;
        } catch (Exception e) {
            log.debug("审批挂起判定失败（按未等待处理）: subTaskId={}, err={}", subTaskId, e.getMessage());
            return false;
        }
    }

    /**
     * 是否为平台内执行（{@code API_KEY_LLM}）。
     *
     * <p>{@code access_type} 为空的历史行按平台内处理——保持引入 G-006 外部 attempt 之前的行为，
     * 避免把存量行误判成外部执行而永久跳过补偿。</p>
     */
    private static boolean isPlatformExecution(AgentExecutionRecord record) {
        return record.getAccessType() == null || record.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

    private void compensateRecordAtomically(AgentExecutionRecord record, String errorMessage) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!agentExecutionRecordService.markTimeout(record.getId())) {
                    return;
                }

                SubTask subTask = subTaskService.getById(record.getSubTaskId());
                if (subTask != null && subTask.getStatus() == SubTaskStatus.IN_PROGRESS) {
                    // 关键调度节点：执行超时判定（用户可观测）——后续经 BLOCKED →
                    // 失败回退/孤儿巡检/人工改派链重新分发，时间线先行留痕“超时未完成”
                    try {
                        taskTimelineService.recordEvent(
                                subTask.getTaskId(),
                                subTask.getId(),
                                TimelineEventCode.SUB_TASK_EXECUTION_TIMEOUT_REASSIGN.code(),
                                AgentRole.SYSTEM,
                                record.getAgentId(),
                                Map.of(
                                        "previousAgentId", record.getAgentId() != null ? record.getAgentId() : "",
                                        "timeoutMinutes", executionProperties.getRunningTimeoutMinutes(),
                                        "eventId", record.getEventId() != null ? record.getEventId() : ""));
                    } catch (Exception timelineEx) {
                        log.warn("执行超时时间线记录失败（不阻断补偿主链路）: subTaskId={}, err={}",
                                record.getSubTaskId(), timelineEx.getMessage());
                    }
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

        // N11 阈值回退计数：超时被视为执行失败（Paperclip 阶段一·4：归 recovery 真失败配额）。
        // 仅对 CLI_CLIENT Agent 累加；SQL 条件已限定 access_type=CLI_CLIENT，误调不会写库。
        Long agentId = record.getAgentId();
        if (agentId != null) {
            failureTracker.recordFailure(agentId, FailureCategory.RECOVERY);
        }
    }
}
