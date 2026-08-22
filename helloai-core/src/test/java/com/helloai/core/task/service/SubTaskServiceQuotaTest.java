package com.helloai.core.task.service;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import com.helloai.core.task.service.AttachmentService;
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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
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
    @Mock private ReviewRecordMapper reviewRecordMapper;
    @Mock private ImplicitScoreCalculator implicitScoreCalculator;
    @Mock private RewardService rewardService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private AgentService agentService;
    @Mock private ConcurrencyQuotaService concurrencyQuotaService;
    @Mock private AttachmentService attachmentService;
    @SuppressWarnings("unchecked")
    @Mock private org.springframework.beans.factory.ObjectProvider<AttachmentService> attachmentServiceProvider;

    private SubTaskService subTaskService;
    private AgentDispatchProperties dispatchProps;

    @BeforeEach
    void setUp() {
        dispatchProps = new AgentDispatchProperties();
        dispatchProps.setEnforceMaxConcurrent(true);
        subTaskService = spy(new SubTaskServiceImpl(
                agentOutboxService, agentInboxService, agentServiceProvider,
                heartbeatService, reviewRecordMapper, implicitScoreCalculator,
                rewardService, applicationEventPublisher, taskTimelineService,
                dispatchProps, concurrencyQuotaService, attachmentServiceProvider));
        // §6.140 收口：行锁改走 AgentService.lockByIdForUpdate（ObjectProvider 懒解析）；
        // lenient：状态校验失败路径不触发行锁，避免 UnnecessaryStubbing
        lenient().when(agentServiceProvider.getIfAvailable()).thenReturn(agentService);
    }

    private void stubPendingSubTask() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setStatus(SubTaskStatus.PENDING);
        doReturn(subTask).when(subTaskService).getById(SUB_TASK_ID);
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
        verify(subTaskService, never()).changeStatus(anyLong(), any(SubTaskStatus.class), anyLong());
    }

    @Test
    @DisplayName("未满：锁后正常分配为 ASSIGNED 并落库")
    void shouldAssignWhenHasCapacity() {
        stubPendingSubTask();
        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
        when(concurrencyQuotaService.canAccept(AGENT_ID)).thenReturn(true);

        subTaskService.assignNext(AGENT_ID, SUB_TASK_ID);

        verify(agentService).lockByIdForUpdate(AGENT_ID);
        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubTaskStatus.ASSIGNED);
        assertThat(captor.getValue().getAssignedAgentId()).isEqualTo(AGENT_ID);
    }

    @Test
    @DisplayName("enforceMaxConcurrent=false：满额也放行（E2 前行为）")
    void shouldIgnoreQuotaWhenDisabled() {
        dispatchProps.setEnforceMaxConcurrent(false);
        stubPendingSubTask();
        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
        // canAccept 默认 false（Mockito boolean 默认值），关闭开关后不应被调用
        subTaskService.assignNext(AGENT_ID, SUB_TASK_ID);

        verify(agentService).lockByIdForUpdate(AGENT_ID);
        verify(concurrencyQuotaService, never()).canAccept(anyLong());
        verify(subTaskService).updateById(any(SubTask.class));
    }

    @Test
    @DisplayName("非 PENDING：状态校验先于加锁，不锁行直接拒绝")
    void shouldValidateStatusBeforeLock() {
        SubTask done = new SubTask();
        done.setId(SUB_TASK_ID);
        done.setStatus(SubTaskStatus.DONE);
        doReturn(done).when(subTaskService).getById(SUB_TASK_ID);

        assertThatThrownBy(() -> subTaskService.assignNext(AGENT_ID, SUB_TASK_ID))
                .isInstanceOf(BizException.class);

        verify(agentService, never()).lockByIdForUpdate(anyLong());
    }
}
