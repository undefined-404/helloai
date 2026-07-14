package com.helloai.core.agent.command;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import com.helloai.core.service.AgentExecutionRecordService;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.SubTaskService;
import com.helloai.core.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2E N6：{@link ExecutionCommandService} 按 {@code dispatch-mode} 分发的单元测试。
 *
 * <p>覆盖 4 种 dispatch-mode + fail-fast：</p>
 * <ol>
 *     <li>{@code NONE}：只落库，事件与 MQ 均零调用（默认，与 consumer-mode=POLLER 配套）</li>
 *     <li>{@code EVENT}：只发本地事件，MQ 零调用</li>
 *     <li>{@code MQ}：只发 MQ，本地事件零调用</li>
 *     <li>{@code BOTH}：本地事件与 MQ 各调 1 次</li>
 *     <li>{@code MQ} + Publisher Bean 缺失 → 运行期 fail-fast（{@link IllegalStateException}），
 *         而不是隐式回退到 EVENT / NONE</li>
 * </ol>
 *
 * <p>本测试不启动 Spring 容器，也不引入 RabbitMQ，全部基于 Mockito 装配。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCommandService dispatch-mode")
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
    private ExecutionCommandMqPublisher mqPublisher;
    @Mock
    private ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider;

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
                applicationEventPublisher, executionProperties, mqPublisherProvider);
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
        @DisplayName("NONE：只落库，事件与 MQ 均零调用")
        void shouldDispatchNothingWhenNone() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.NONE);
            primeCommonMocks();

            ExecutionCommand command = service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            assertEquals(RECORD_ID, command.getRecordId());
            verify(applicationEventPublisher, never()).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(mqPublisherProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("EVENT：只发本地事件，MQ 零调用")
        void shouldDispatchEventOnlyWhenEvent() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.EVENT);
            primeCommonMocks();

            service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            verify(applicationEventPublisher, times(1)).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(mqPublisherProvider, never()).getIfAvailable();
        }

        @Test
        @DisplayName("MQ：只发 MQ，本地事件零调用")
        void shouldDispatchMqOnlyWhenMq() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.MQ);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            primeCommonMocks();

            service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            verify(applicationEventPublisher, never()).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(mqPublisher, times(1)).publish(any(ExecutionCommand.class));
        }

        @Test
        @DisplayName("BOTH：本地事件与 MQ 各调 1 次")
        void shouldDispatchBothWhenBoth() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.BOTH);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            primeCommonMocks();

            service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER);

            verify(applicationEventPublisher, times(1)).publishEvent(any(ExecutionCommandCreatedEvent.class));
            verify(mqPublisher, times(1)).publish(any(ExecutionCommand.class));
        }
    }

    @Nested
    @DisplayName("fail-fast")
    class FailFast {

        @Test
        @DisplayName("MQ 但 Publisher Bean 不可用 → IllegalStateException，不隐式回退")
        void shouldFailFastWhenMqButPublisherMissing() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.MQ);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(null);
            primeCommonMocks();

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER));

            assertTrue(ex.getMessage().contains("dispatch-mode=MQ"),
                    "异常消息应显式包含 dispatch-mode，便于线上诊断: " + ex.getMessage());
            verify(applicationEventPublisher, never()).publishEvent(any(ExecutionCommandCreatedEvent.class));
        }

        @Test
        @DisplayName("BOTH 但 Publisher Bean 不可用 → 本地事件已发但 MQ fail-fast")
        void shouldFailFastWhenBothButPublisherMissing() {
            executionProperties.setDispatchMode(AgentExecutionProperties.DispatchMode.BOTH);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(null);
            primeCommonMocks();

            assertThrows(IllegalStateException.class,
                    () -> service.createAssignedCommand(SUB_TASK_ID, AGENT_ID, TRIGGER));

            // 本地事件在 MQ 分支之前已发出（BOTH 语义），事务将回滚（此处不 verify 事务，只验证事件确实发过）
            verify(applicationEventPublisher, times(1)).publishEvent(any(ExecutionCommandCreatedEvent.class));
        }
    }
}
