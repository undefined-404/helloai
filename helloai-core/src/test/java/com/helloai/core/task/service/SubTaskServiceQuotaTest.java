package com.helloai.core.task.service;

import com.helloai.core.agent.service.RewardService;
import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.WatchdogProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.impl.SubTaskNotificationServiceImpl;
import com.helloai.core.task.service.impl.SubTaskQueryServiceImpl;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import com.helloai.core.task.service.impl.SubTaskStateMachineServiceImpl;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubTaskService.assignNext 并发额度防护单元测试（E2）。
 *
 * <p>验证原子防线三件事：
 * <ul>
 *   <li>落库前 FOR UPDATE 锁 agent 行（串行化同一 Agent 的并发派发）</li>
 *   <li>满额抛 AgentUnavailableException（不计熔断、走 fallback 换人）且不落库</li>
 *   <li>enforceMaxConcurrent=false 时放行（E2 前行为）</li>
 * </ul>
 * </p>
 *
 * <p><b>RM9-b 装配口径变更（断言语义未改）</b>：{@code assignNext} 已迁入
 * {@link SubTaskStateMachineService}，行读取/写入改设到同域 {@code SubTaskMapper}；
 * 「不落库」的判定由门面 {@code changeStatus} 校验改为状态机类上的 {@code changeStatus}
 * 校验（spy 以记录自调用），断言语义一一对应。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskService.assignNext 并发额度防护（E2）")
class SubTaskServiceQuotaTest {

    private static final long SUB_TASK_ID = 1L;
    private static final long AGENT_ID = 100L;

    @Mock private AgentOutboxService agentOutboxService;
    @Mock private AgentInboxService agentInboxService;
    @Mock private org.springframework.beans.factory.ObjectProvider<AgentService> agentServiceProvider;
    @Mock private HeartbeatService heartbeatService;
    @Mock private ReviewPort reviewPort;
    @Mock private ImplicitScoreCalculator implicitScoreCalculator;
    @Mock private RewardService rewardService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private AgentService agentService;
    @Mock private ConcurrencyQuotaService concurrencyQuotaService;
    @Mock private AttachmentService attachmentService;
    @SuppressWarnings("unchecked")
    @Mock private org.springframework.beans.factory.ObjectProvider<AttachmentService> attachmentServiceProvider;
    // LOG-20260904-009：装箱出口 requiredSkillsOf 懒解析 TaskService（quota 用例不触达，防 NPE）
    @Mock private org.springframework.beans.factory.ObjectProvider<TaskService> taskServiceProvider;
    @Mock private AgentEventRecorder agentEventRecorder;
    // 共享预算原子累加 mapper（状态机行读写谓词，quota 用例经状态机触达）
    @Mock private SubTaskMapper subTaskMapper;
    // 执行会话服务（quota 用例不触达，仅防 NPE）
    @Mock private AgentSessionService agentSessionService;

    private SubTaskService subTaskService;
    private SubTaskStateMachineService stateMachineService;
    private AgentDispatchProperties dispatchProps;

    @BeforeEach
    void setUp() {
        dispatchProps = new AgentDispatchProperties();
        dispatchProps.setEnforceMaxConcurrent(true);
        SubTaskQueryService queryService = new SubTaskQueryServiceImpl(
                subTaskMapper, taskServiceProvider, reviewPort);
        SubTaskNotificationService notificationService = new SubTaskNotificationServiceImpl(
                agentInboxService, agentServiceProvider, applicationEventPublisher, reviewPort, queryService);
        stateMachineService = spy(new SubTaskStateMachineServiceImpl(
                subTaskMapper, notificationService, agentOutboxService, heartbeatService,
                concurrencyQuotaService, dispatchProps,
                // 租约看门狗配置（assignNext 不涉及租约，默认值即可）
                new WatchdogProperties(), taskTimelineService, agentServiceProvider,
                attachmentServiceProvider, agentEventRecorder, implicitScoreCalculator,
                rewardService, reviewPort, applicationEventPublisher));
        SubTaskLeaseService leaseService = mock(SubTaskLeaseService.class);
        subTaskService = new SubTaskServiceImpl(
                subTaskMapper, queryService, stateMachineService, leaseService);
        // §6.140 收口：行锁改走 AgentService.lockByIdForUpdate（ObjectProvider 懒解析）；
        // lenient：状态校验失败路径不触发行锁，避免 UnnecessaryStubbing
        lenient().when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
    }

    private void stubPendingSubTask() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setStatus(SubTaskStatus.PENDING);
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask);
    }

    @Test
    @DisplayName("满额：锁 agent 行后抛 AgentUnavailableException，任务不落库")
    void shouldRejectWhenQuotaFull() {
        stubPendingSubTask();
        when(concurrencyQuotaService.canAccept(AGENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> subTaskService.assignNext(AGENT_ID, SUB_TASK_ID))
                .isInstanceOf(AgentUnavailableException.class);

        verify(agentService).lockByIdForUpdate(AGENT_ID);
        verify(concurrencyQuotaService).canAccept(AGENT_ID);
        verify(stateMachineService, never()).changeStatus(anyLong(), any(SubTaskStatus.class), anyLong());
    }

    @Test
    @DisplayName("未满：锁后正常分配为 ASSIGNED 并落库")
    void shouldAssignWhenHasCapacity() {
        stubPendingSubTask();
        when(subTaskMapper.updateById(any(SubTask.class))).thenReturn(1);
        when(concurrencyQuotaService.canAccept(AGENT_ID)).thenReturn(true);

        subTaskService.assignNext(AGENT_ID, SUB_TASK_ID);

        verify(agentService).lockByIdForUpdate(AGENT_ID);
        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubTaskStatus.ASSIGNED);
        assertThat(captor.getValue().getAssignedAgentId()).isEqualTo(AGENT_ID);
    }

    @Test
    @DisplayName("enforceMaxConcurrent=false：满额也放行（E2 前行为）")
    void shouldIgnoreQuotaWhenDisabled() {
        dispatchProps.setEnforceMaxConcurrent(false);
        stubPendingSubTask();
        when(subTaskMapper.updateById(any(SubTask.class))).thenReturn(1);
        // canAccept 默认 false（Mockito boolean 默认值），关闭开关后不应被调用
        subTaskService.assignNext(AGENT_ID, SUB_TASK_ID);

        verify(agentService).lockByIdForUpdate(AGENT_ID);
        verify(concurrencyQuotaService, never()).canAccept(anyLong());
        verify(subTaskMapper).updateById(any(SubTask.class));
    }

    @Test
    @DisplayName("非 PENDING：状态校验先于加锁，不锁行直接拒绝")
    void shouldValidateStatusBeforeLock() {
        SubTask done = new SubTask();
        done.setId(SUB_TASK_ID);
        done.setStatus(SubTaskStatus.DONE);
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(done);

        assertThatThrownBy(() -> subTaskService.assignNext(AGENT_ID, SUB_TASK_ID))
                .isInstanceOf(BizException.class);

        verify(agentService, never()).lockByIdForUpdate(anyLong());
    }
}
