package com.helloai.core.agent.command;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.shared.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentCommandOutboxService;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.service.impl.ExecutionCommandServiceImpl;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2H ②a 适配后的 {@link ExecutionCommandService} 单元测试。
 *
 * <p>本测试覆盖：</p>
 * <ul>
 *     <li>EVENT 路径仍发本地事件且不写 outbox；</li>
 *     <li>NONE 路径零调用；</li>
 *     <li>MQ 路径改写 outbox.createPending；</li>
 *     <li>子任务已有进行中执行记录时拒绝创建；</li>
 *     <li>DB 行锁 + hasPendingOrRunning 双重防重。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCommandService (Phase 2H ②a)")
class ExecutionCommandServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private AgentCommandOutboxService agentCommandOutboxService;

    @Mock
    private AgentEventRecorder agentEventRecorder;

    private ExecutionCommandService executionCommandService;

    @BeforeEach
    void setUp() {
        executionCommandService = new ExecutionCommandServiceImpl(
                subTaskService, agentService, agentExecutionRecordService, taskTimelineService,
                applicationEventPublisher, executionProperties, agentCommandOutboxService, agentEventRecorder);
    }

    @Test
    @DisplayName("为 ASSIGNED 子任务创建 execution command 并发布事件")
    void shouldCreateExecutionCommandAndPublishEvent() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);

        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(44L);
        record.setSubTaskId(22L);
        record.setStatus(ExecutionStatus.PENDING);

        when(subTaskService.getByIdForUpdate(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(agent);
        when(agentExecutionRecordService.hasPendingOrRunning(22L)).thenReturn(false);
        when(agentExecutionRecordService.createPending(any(), eq(22L), eq(11L),
                eq(AgentAccessType.API_KEY_LLM), eq("assigned"))).thenReturn(record);
        when(executionProperties.isDispatchEvent()).thenReturn(true);
        when(executionProperties.isDispatchMq()).thenReturn(false);
        when(executionProperties.getDispatchMode()).thenReturn(AgentExecutionProperties.DispatchMode.EVENT);
        when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.EVENT);

        ExecutionCommand command = executionCommandService.createAssignedCommand(22L, 11L, "assigned", List.of());

        assertEquals(44L, command.getRecordId());
        assertEquals(22L, command.getSubTaskId());
        assertEquals(11L, command.getAgentId());
        assertEquals("assigned", command.getTrigger());
        assertEquals(AgentAccessType.API_KEY_LLM, command.getAccessType());
        assertNotNull(command.getEventId());

        verify(taskTimelineService).recordEvent(
                33L, 22L, "sub_task_execution_command_created", AgentRole.SYSTEM, 11L,
                Map.of(
                        "trigger", "assigned",
                        "recordId", 44L,
                        "eventId", command.getEventId(),
                        "accessType", "API_KEY_LLM"));

        ArgumentCaptor<ExecutionCommandCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(ExecutionCommandCreatedEvent.class);
        verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(command, eventCaptor.getValue().getCommand());

        // EVENT 路径下不应写 outbox
        verify(agentCommandOutboxService, never()).createPending(any(), any());
    }

    @Test
    @DisplayName("dispatch-mode=NONE 时创建 execution command 后不发布本地事件")
    void shouldNotPublishEventInPollerMode() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);

        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(44L);
        record.setSubTaskId(22L);
        record.setStatus(ExecutionStatus.PENDING);

        when(subTaskService.getByIdForUpdate(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(agent);
        when(agentExecutionRecordService.hasPendingOrRunning(22L)).thenReturn(false);
        when(agentExecutionRecordService.createPending(any(), eq(22L), eq(11L),
                eq(AgentAccessType.API_KEY_LLM), eq("assigned"))).thenReturn(record);
        when(executionProperties.isDispatchEvent()).thenReturn(false);
        when(executionProperties.isDispatchMq()).thenReturn(false);
        when(executionProperties.getDispatchMode()).thenReturn(AgentExecutionProperties.DispatchMode.NONE);
        when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);

        ExecutionCommand command = executionCommandService.createAssignedCommand(22L, 11L, "assigned", List.of());

        assertEquals(44L, command.getRecordId());
        verify(applicationEventPublisher, never()).publishEvent(any());
        verify(agentCommandOutboxService, never()).createPending(any(), any());
    }

    @Test
    @DisplayName("dispatch-mode=MQ 时调用 outbox.createPending，不发本地事件")
    void shouldEnqueueOutboxWhenDispatchMq() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);

        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(44L);
        record.setSubTaskId(22L);
        record.setStatus(ExecutionStatus.PENDING);

        when(subTaskService.getByIdForUpdate(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(agent);
        when(agentExecutionRecordService.hasPendingOrRunning(22L)).thenReturn(false);
        when(agentExecutionRecordService.createPending(any(), eq(22L), eq(11L),
                eq(AgentAccessType.API_KEY_LLM), eq("assigned"))).thenReturn(record);
        when(executionProperties.isDispatchEvent()).thenReturn(false);
        when(executionProperties.isDispatchMq()).thenReturn(true);
        when(executionProperties.getDispatchMode()).thenReturn(AgentExecutionProperties.DispatchMode.MQ);
        when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);

        executionCommandService.createAssignedCommand(22L, 11L, "assigned", List.of());

        verify(applicationEventPublisher, never()).publishEvent(any(ExecutionCommandCreatedEvent.class));
        ArgumentCaptor<ExecutionCommand> cmdCaptor = ArgumentCaptor.forClass(ExecutionCommand.class);
        ArgumentCaptor<ExecutionCommandMqMessage> msgCaptor = ArgumentCaptor.forClass(ExecutionCommandMqMessage.class);
        verify(agentCommandOutboxService).createPending(cmdCaptor.capture(), msgCaptor.capture());
        assertEquals("API_KEY_LLM", msgCaptor.getValue().getAccessType());
        assertEquals(22L, cmdCaptor.getValue().getSubTaskId());
    }

    @Test
    @DisplayName("子任务已有进行中执行记录时拒绝重复创建 execution command")
    void shouldRejectWhenPendingOrRunningRecordExists() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);

        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        when(subTaskService.getByIdForUpdate(22L)).thenReturn(subTask);
        when(agentExecutionRecordService.hasPendingOrRunning(22L)).thenReturn(true);

        assertThatThrownBy(() -> executionCommandService.createAssignedCommand(22L, 11L, "assigned", List.of()))
                .isInstanceOf(com.helloai.common.base.BizException.class)
                .hasMessageContaining("进行中的执行记录");

        verify(agentCommandOutboxService, never()).createPending(any(), any());
    }

    @Test
    @DisplayName("P2-1: 防重保护应同时使用 DB 行锁 + hasPendingOrRunning 双重检查")
    void shouldUseBothRowLockAndHasPendingOrRunningForDuplicatePrevention() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);

        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(44L);
        record.setSubTaskId(22L);
        record.setStatus(ExecutionStatus.PENDING);

        when(subTaskService.getByIdForUpdate(22L)).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(agent);
        when(agentExecutionRecordService.hasPendingOrRunning(22L)).thenReturn(false);
        when(agentExecutionRecordService.createPending(any(), eq(22L), eq(11L),
                eq(AgentAccessType.API_KEY_LLM), eq("assigned"))).thenReturn(record);
        when(executionProperties.isDispatchEvent()).thenReturn(true);
        when(executionProperties.isDispatchMq()).thenReturn(false);
        when(executionProperties.getDispatchMode()).thenReturn(AgentExecutionProperties.DispatchMode.EVENT);
        when(executionProperties.getConsumerMode()).thenReturn(AgentExecutionProperties.ConsumerMode.EVENT);

        executionCommandService.createAssignedCommand(22L, 11L, "assigned", List.of());

        var inOrder = org.mockito.Mockito.inOrder(subTaskService, agentExecutionRecordService);
        inOrder.verify(subTaskService).getByIdForUpdate(22L);
        inOrder.verify(agentExecutionRecordService).hasPendingOrRunning(22L);
    }
}
