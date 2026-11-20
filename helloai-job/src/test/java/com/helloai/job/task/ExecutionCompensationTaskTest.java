package com.helloai.job.task;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.agent.observability.FailureCategory;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCompensationTask")
class ExecutionCompensationTaskTest {

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;

    @Mock
    private ExecutionResultHandler executionResultHandler;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ExternalAgentFailureTracker failureTracker;

    @InjectMocks
    private ExecutionCompensationTask executionCompensationTask;

    @BeforeEach
    void setUp() {
        when(executionProperties.getPendingTimeoutMinutes()).thenReturn(5);
        when(executionProperties.getRunningTimeoutMinutes()).thenReturn(10);
        // MA-10 改动 3：无论接管与否本段都会调用无进展扫描（观察期也要拿分布），
        // 默认返回空列表，让既有的三条用例不受影响。
        lenient().when(agentExecutionRecordService.listRunningWithoutProgressBefore(any()))
                .thenReturn(List.of());
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /** MA-10 改动 3 的公共构造：一条超阈值但「无进展」的 RUNNING 记录。 */
    private AgentExecutionRecord runningWithoutProgress(Long subTaskId, Long agentId) {
        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(301L);
        record.setEventId("evt-no-progress");
        record.setSubTaskId(subTaskId);
        record.setAgentId(agentId);
        record.setStatus(ExecutionStatus.RUNNING);
        // lastProgressAt 保留 null ⇒ 扫描 SQL 侧回落 start_time（存量行等价），
        // 本测试只验证任务侧分支，不验证 SQL 条件（那由 Mapper 单测覆盖）。
        record.setStartTime(OffsetDateTime.now().minusMinutes(90));
        return record;
    }

    @Test
    @DisplayName("★ 无进展接管：enforced=true 时旧「总时长」判据不再参与，无进展记录被处置")
    void shouldTakeOverByNoProgressWhenEnforced() {
        when(executionProperties.isNoProgressDetectionEnforced()).thenReturn(true);
        when(executionProperties.getNoProgressTimeoutMinutes()).thenReturn(45);

        // 旧判据本会命中（startTime 90 分钟前 > runningTimeout 10 分钟），
        // 但接管后 runningStuck 必须被清空——否则一条记录会被处置两次。
        AgentExecutionRecord staleByTotalTime = runningWithoutProgress(701L, 909L);
        when(agentExecutionRecordService.listByStatusStartedBefore(eq(ExecutionStatus.RUNNING), any()))
                .thenReturn(List.of(staleByTotalTime));

        AgentExecutionRecord noProgress = runningWithoutProgress(702L, 909L);
        when(agentExecutionRecordService.listRunningWithoutProgressBefore(any()))
                .thenReturn(List.of(noProgress));

        SubTask subTask = new SubTask();
        subTask.setId(702L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(702L)).thenReturn(subTask);
        when(agentExecutionRecordService.markTimeout(301L)).thenReturn(true);

        executionCompensationTask.compensate();

        // 只在无进展那一条上处置了一次；旧判据那条被完全跳过
        verify(agentExecutionRecordService).markTimeout(anyLong());
        verify(executionResultHandler).handleFailure(
                eq(702L),
                org.mockito.ArgumentMatchers.isNull(),
                argThat(ex -> ex != null
                        && ex.getMessage() != null
                        && ex.getMessage().contains("no-progress timeout")
                        && ex.getMessage().contains("45")));
    }

    @Test
    @DisplayName("★ 观察期：enforced=false 时只记日志、不处置（与 MA-4 第一段同源纪律）")
    void shouldOnlyObserveWhenNotEnforced() {
        when(executionProperties.isNoProgressDetectionEnforced()).thenReturn(false);
        when(executionProperties.getNoProgressTimeoutMinutes()).thenReturn(45);

        AgentExecutionRecord noProgress = runningWithoutProgress(703L, 909L);
        when(agentExecutionRecordService.listRunningWithoutProgressBefore(any()))
                .thenReturn(List.of(noProgress));

        // 旧判据不命中（未超 runningTimeout）
        when(agentExecutionRecordService.listByStatusStartedBefore(eq(ExecutionStatus.RUNNING), any()))
                .thenReturn(List.of());

        executionCompensationTask.compensate();

        // 只观察：不 markTimeout、不回写失败（否则首日就会误杀）
        verify(agentExecutionRecordService, never()).markTimeout(anyLong());
        verify(executionResultHandler, never()).handleFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("★ 审批豁免：context.pendingApproval 存在 ⇒ 无进展不判死")
    void shouldExemptAwaitingApproval() {
        when(executionProperties.isNoProgressDetectionEnforced()).thenReturn(true);
        when(executionProperties.getNoProgressTimeoutMinutes()).thenReturn(45);

        AgentExecutionRecord noProgress = runningWithoutProgress(704L, 909L);
        when(agentExecutionRecordService.listRunningWithoutProgressBefore(any()))
                .thenReturn(List.of(noProgress));

        SubTask subTask = new SubTask();
        subTask.setId(704L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        // 只判键存在性（不依赖审批业务模型）——这是 MA-9 与 MA-10 可独立回滚的边界
        subTask.setContext(Map.of(SubTaskContextKeys.PENDING_APPROVAL, Map.of("status", "PENDING")));
        when(subTaskService.getById(704L)).thenReturn(subTask);

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService, never()).markTimeout(anyLong());
        verify(executionResultHandler, never()).handleFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("外部执行记录无进展 ⇒ 跳过（G-006：外部时长由租约/人工节奏决定）")
    void shouldSkipExternalAgentForNoProgress() {
        when(executionProperties.isNoProgressDetectionEnforced()).thenReturn(true);
        when(executionProperties.getNoProgressTimeoutMinutes()).thenReturn(45);

        AgentExecutionRecord external = runningWithoutProgress(705L, 909L);
        external.setAccessType(com.helloai.common.constant.AgentAccessType.CLI_CLIENT);
        when(agentExecutionRecordService.listRunningWithoutProgressBefore(any()))
                .thenReturn(List.of(external));

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService, never()).markTimeout(anyLong());
    }

    @Test
    @DisplayName("PENDING 超时且子任务非 IN_PROGRESS 时只标记 TIMEOUT，不推进状态")
    void shouldMarkPendingTimeoutWithoutBlockingWhenSubTaskNotInProgress() {
        AgentExecutionRecord pendingRecord = new AgentExecutionRecord();
        pendingRecord.setId(101L);
        pendingRecord.setEventId("evt-pending");
        pendingRecord.setSubTaskId(201L);

        SubTask subTask = new SubTask();
        subTask.setId(201L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);

        when(agentExecutionRecordService.listByStatusCreatedBefore(any(), any()))
                .thenReturn(List.of(pendingRecord));
        when(agentExecutionRecordService.listByStatusStartedBefore(any(), any()))
                .thenReturn(List.of());
        when(agentExecutionRecordService.markTimeout(101L)).thenReturn(true);
        when(subTaskService.getById(201L)).thenReturn(subTask);

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService).markTimeout(101L);
        verify(executionResultHandler, never()).handleFailure(anyLong(), any(), any());
        // 非 IN_PROGRESS 子任务不记执行超时改派事件（仅记录超时标记）
        verify(taskTimelineService, never()).recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("RUNNING 超时且子任务仍在 IN_PROGRESS 时回写失败状态")
    void shouldHandleFailureWhenRunningRecordTimesOut() {
        AgentExecutionRecord runningRecord = new AgentExecutionRecord();
        runningRecord.setId(102L);
        runningRecord.setEventId("evt-running");
        runningRecord.setSubTaskId(202L);
        runningRecord.setAgentId(909L);
        runningRecord.setStartTime(OffsetDateTime.now().minusMinutes(30));

        SubTask subTask = new SubTask();
        subTask.setId(202L);
        subTask.setTaskId(502L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);

        when(agentExecutionRecordService.listByStatusCreatedBefore(eq(ExecutionStatus.PENDING), any()))
                .thenReturn(List.of());
        when(agentExecutionRecordService.listByStatusStartedBefore(eq(ExecutionStatus.RUNNING), any()))
                .thenReturn(List.of(runningRecord));
        when(agentExecutionRecordService.markTimeout(102L)).thenReturn(true);
        when(subTaskService.getById(202L)).thenReturn(subTask);

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService).markTimeout(102L);
        verify(executionResultHandler).handleFailure(
                eq(202L),
                org.mockito.ArgumentMatchers.isNull(),
                argThat(ex -> ex != null
                        && ex.getMessage() != null
                        && ex.getMessage().contains("RUNNING timeout")));
        // 关键调度节点：执行超时改派事件先于失败回写留痕（用户可观测）
        verify(taskTimelineService).recordEvent(
                502L, 202L, "sub_task_execution_timeout_reassign", AgentRole.SYSTEM, 909L,
                Map.of("previousAgentId", 909L,
                        "timeoutMinutes", 10,
                        "eventId", "evt-running"));
        // N11 阈值回退：超时计为执行失败，累加原 Agent 的 recovery 真失败配额
        verify(failureTracker).recordFailure(909L, FailureCategory.RECOVERY);
    }

    @Test
    @DisplayName("未超时记录不会触发任何补偿")
    void shouldIgnoreWhenNoTimedOutRecords() {
        when(agentExecutionRecordService.listByStatusCreatedBefore(eq(ExecutionStatus.PENDING), any()))
                .thenReturn(List.of());
        when(agentExecutionRecordService.listByStatusStartedBefore(eq(ExecutionStatus.RUNNING), any()))
                .thenReturn(List.of());

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService, never()).markTimeout(anyLong());
        verify(executionResultHandler, never()).handleFailure(anyLong(), any(), any());
    }
}
