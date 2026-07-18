package com.helloai.job.task;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
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
    private AgentExecutionRecordMapper executionRecordMapper;

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
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ExecutionCompensationTask executionCompensationTask;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), anyLong(), any())).thenReturn(true);
        when(executionProperties.getPendingTimeoutMinutes()).thenReturn(5);
        when(executionProperties.getRunningTimeoutMinutes()).thenReturn(10);
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
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

        when(executionRecordMapper.selectByStatusAndCreateTimeBefore(any(), any()))
                .thenReturn(List.of(pendingRecord));
        when(executionRecordMapper.selectByStatusAndStartTimeBefore(any(), any()))
                .thenReturn(List.of());
        when(agentExecutionRecordService.markTimeout(101L)).thenReturn(true);
        when(subTaskService.getById(201L)).thenReturn(subTask);

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService).markTimeout(101L);
        verify(executionResultHandler, never()).handleFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("RUNNING 超时且子任务仍在 IN_PROGRESS 时回写失败状态")
    void shouldHandleFailureWhenRunningRecordTimesOut() {
        AgentExecutionRecord runningRecord = new AgentExecutionRecord();
        runningRecord.setId(102L);
        runningRecord.setEventId("evt-running");
        runningRecord.setSubTaskId(202L);
        runningRecord.setStartTime(OffsetDateTime.now().minusMinutes(30));

        SubTask subTask = new SubTask();
        subTask.setId(202L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);

        when(executionRecordMapper.selectByStatusAndCreateTimeBefore(eq(ExecutionStatus.PENDING), any()))
                .thenReturn(List.of());
        when(executionRecordMapper.selectByStatusAndStartTimeBefore(eq(ExecutionStatus.RUNNING), any()))
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
    }

    @Test
    @DisplayName("未超时记录不会触发任何补偿")
    void shouldIgnoreWhenNoTimedOutRecords() {
        when(executionRecordMapper.selectByStatusAndCreateTimeBefore(eq(ExecutionStatus.PENDING), any()))
                .thenReturn(List.of());
        when(executionRecordMapper.selectByStatusAndStartTimeBefore(eq(ExecutionStatus.RUNNING), any()))
                .thenReturn(List.of());

        executionCompensationTask.compensate();

        verify(agentExecutionRecordService, never()).markTimeout(anyLong());
        verify(executionResultHandler, never()).handleFailure(anyLong(), any(), any());
    }
}
