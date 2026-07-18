package com.helloai.core.agent.command;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.shared.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentCommandOutboxService;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2H ②a：{@link ExecutionCommandService} 按 {@code dispatch-mode} 分发的单元测试。
 *
 * <p>Phase 2H 关键变化：MQ / BOTH 分支不再调
 * {@link ExecutionCommandMqPublisher}，改为调用
 * {@link AgentCommandOutboxService#createPending(ExecutionCommand, ExecutionCommandMqMessage)}
 * 写 outbox 行（由 OutboxRelayTask 异步发 MQ）。测试重点相应迁移到 outbox 调用。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *     <li>{@code NONE}：只落库，事件与 outbox 均零调用</li>
 *     <li>{@code EVENT}：只发本地事件，outbox 零调用</li>
 *     <li>{@code MQ}：只写 outbox（PENDING），本地事件零调用</li>
 *     <li>{@code BOTH}：本地事件 + outbox 各调 1 次</li>
 * </ol>
 *
 * <p>本测试不启动 Spring 容器，也不引入 RabbitMQ，全部基于 Mockito 装配。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCommandService dispatch-mode (Phase 2H ②a)")
class ExecutionCommandServiceDispatchTest {

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
    private AgentCommandOutboxService agentCommandOutboxService;

    private final AgentExecutionProperties executionProperties = new AgentExecutionProperties();

    private ExecutionCommandService service;

    private static final Long SUB_TASK_ID = 11L;
    private static final Long AGENT_ID = 22L;
    private static final Long RECORD_ID = 1001L;
    private static final Long TASK_ID = 999L;
    private static final String TRIGGER = "assigned";

    @BeforeEach
    void setUp() {
        service = new ExecutionCommandService(
                subTaskService, agentService, agentExecutionRecordService, taskTimelineService,
                applicationEventPublisher, executionProperties, agentCommandOutboxService);
    }

    /**
     * 组装最小可用的 SubTask / Agent / Record，让 createAssignedCommand 顺利跑到分发逻辑。
     */
    private void primeCommonMocks() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        subTask.setAssignedAgent(AGENT_ID);
        when(subTaskService.getByIdForUpdate(SUB_TASK_ID)).thenReturn(subTask);

        when(agentExecutionRecordService.hasPendingOrRunning(SUB_TASK_ID)).thenReturn(false);

        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        when(agentService.getById(AGENT_ID)).thenReturn(agent);

        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setId(RECORD_ID);
        when(agentExecutionRecordService.createPending(anyString(), eq(SUB_TASK_ID), eq(AGENT_ID),
                eq(AgentAccessType.API_KEY_LLM), eq(TRIGGER))).thenReturn(record);
    }

    @Nested
    @DisplayName("dispatch-mode 分发")
    class DispatchByMode {

        @Test
        @DisplayName("NONE：只落库，事件与 outbox 均零调用")
        void shouldDispatchNothingWhenNone() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.NONE);
            primeCommonMocks();

            ExecutionCommand command = service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            assertEquals(RECORD_ID, command.getRecordId());
            verify(applicationEventPublisher, never()).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(agentCommandOutboxService, never()).createPending(any(), any());
        }

        @Test
        @DisplayName("EVENT：只发本地事件，outbox 零调用")
        void shouldDispatchEventOnlyWhenEvent() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.EVENT);
            primeCommonMocks();

            service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            verify(applicationEventPublisher, times(1)).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(agentCommandOutboxService, never()).createPending(any(), any());
        }

        @Test
        @DisplayName("MQ：只写 outbox（PENDING），本地事件零调用")
        void shouldDispatchMqOnlyWhenMq() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.MQ);
            primeCommonMocks();

            service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            verify(applicationEventPublisher, never()).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(agentCommandOutboxService, times(1))
                    .createPending(any(ExecutionCommand.class), any(ExecutionCommandMqMessage.class));
        }

        @Test
        @DisplayName("BOTH：本地事件 + outbox 各调 1 次")
        void shouldDispatchBothWhenBoth() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.BOTH);
            primeCommonMocks();

            service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            verify(applicationEventPublisher, times(1)).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(agentCommandOutboxService, times(1))
                    .createPending(any(ExecutionCommand.class), any(ExecutionCommandMqMessage.class));
        }
    }
}
