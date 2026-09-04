package com.helloai.core.task.service;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.WatchdogProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 0 A3 共享重试预算（attempt_total）单元测试（坑点 3「单一权威」）：
 * 自动驳回返工计入共享预算 / 预算耗尽转 DEAD_LETTER / 人工驳回清零预算 / 熔断禁用逃生口。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskService 共享重试预算（A3）")
class SubTaskServiceReworkBudgetTest {

    private static final long SUB_TASK_ID = 1L;

    @Mock private AgentInboxService agentInboxService;
    @Mock private AgentOutboxService agentOutboxService;
    @Mock private ObjectProvider<AgentService> agentServiceProvider;
    @Mock private HeartbeatService heartbeatService;
    @Mock private ReviewPort reviewPort;
    @Mock private ImplicitScoreCalculator implicitScoreCalculator;
    @Mock private RewardService rewardService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private ConcurrencyQuotaService concurrencyQuotaService;
    @Mock private ObjectProvider<AttachmentService> attachmentServiceProvider;
    // LOG-20260904-009：装箱出口 requiredSkillsOf 懒解析 TaskService（rework 用例不触达，防 NPE）
    @Mock private ObjectProvider<TaskService> taskServiceProvider;
    @Mock private AgentEventRecorder agentEventRecorder;
    @Mock private SubTaskMapper subTaskMapper;

    private AgentDispatchProperties dispatchProps;
    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        dispatchProps = new AgentDispatchProperties();
        subTaskService = spy(new SubTaskServiceImpl(
                agentOutboxService, agentInboxService, agentServiceProvider,
                heartbeatService, reviewPort, implicitScoreCalculator,
                rewardService, applicationEventPublisher, taskTimelineService,
                dispatchProps, concurrencyQuotaService,
                new WatchdogProperties(), attachmentServiceProvider, taskServiceProvider, agentEventRecorder,
                subTaskMapper));
        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
        // 懒解析 bean：附件失效 / 执行者通知路径触达时返回 mock，防 NPE
        when(attachmentServiceProvider.getIfAvailable()).thenReturn(mock(AttachmentService.class));
        when(agentServiceProvider.getIfAvailable()).thenReturn(mock(AgentService.class));
    }

    private SubTask reviewSubTask(Integer attemptTotal, Integer reworkCount) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(9L);
        subTask.setTitle("测试任务");
        subTask.setStatus(SubTaskStatus.REVIEW);
        subTask.setAssignedAgentId(100L);
        subTask.setAttemptTotal(attemptTotal);
        subTask.setReworkCount(reworkCount);
        return subTask;
    }

    @Test
    @DisplayName("预算充足：自动驳回打回成功，attempt_total 原子累加且 reworkCount+1")
    void shouldConsumeBudgetAndReworkWhenBudgetAvailable() {
        doReturn(reviewSubTask(2, 2)).when(subTaskService).getById(SUB_TASK_ID);

        boolean result = subTaskService.rework(SUB_TASK_ID, null);

        assertThat(result).isTrue();
        verify(subTaskMapper).incrementAttemptTotal(eq(SUB_TASK_ID), any(OffsetDateTime.class));
        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubTaskStatus.REWORK);
        assertThat(captor.getValue().getReworkCount()).isEqualTo(3);
        // 熔断副作用不触发
        verify(subTaskService, never()).changeStatus(
                eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull(), anyMap());
    }

    @Test
    @DisplayName("预算耗尽（attempt_total=5）：不再打回，转 DEAD_LETTER + timeline + 人工介入标记")
    void shouldTripCircuitBreakerWhenBudgetExhausted() {
        doReturn(reviewSubTask(5, 0)).when(subTaskService).getById(SUB_TASK_ID);

        boolean result = subTaskService.rework(SUB_TASK_ID, null);

        assertThat(result).isFalse();
        // 预算不再累加
        verify(subTaskMapper, never()).incrementAttemptTotal(anyLong(), any(OffsetDateTime.class));
        // 不打回：熔断副作用（changeStatus/markManualIntervention 内部）产生的 updateById 均非 REWORK
        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(s -> assertThat(s.getStatus()).isNotEqualTo(SubTaskStatus.REWORK));
        // 熔断副作用：REVIEW → DEAD_LETTER + timeline + 人工介入标记
        verify(subTaskService).changeStatus(
                eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull(), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(9L), eq(SUB_TASK_ID), eq("sub_task_dead_letter"),
                eq(AgentRole.SYSTEM), isNull(), anyMap());
        verify(subTaskService).markManualIntervention(eq(SUB_TASK_ID), eq("rework_budget"), anyMap());
    }

    @Test
    @DisplayName("人工驳回（reworkFresh）：清零共享预算 attempt_total（用户拍板新一轮，与死信重派对称）")
    void shouldResetBudgetWhenReworkFresh() {
        doReturn(reviewSubTask(4, 3)).when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.reworkFresh(SUB_TASK_ID, null);

        verify(subTaskMapper).resetAttemptTotal(eq(SUB_TASK_ID), any(OffsetDateTime.class));
        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubTaskStatus.REWORK);
        assertThat(captor.getValue().getReworkCount()).isZero();
    }

    @Test
    @DisplayName("熔断禁用（max-reassign-attempts<=0 逃生口）：打回成功但不累加预算")
    void shouldAllowReworkWhenCircuitBreakerDisabled() {
        dispatchProps.setMaxReassignAttempts(0);
        doReturn(reviewSubTask(99, 1)).when(subTaskService).getById(SUB_TASK_ID);

        boolean result = subTaskService.rework(SUB_TASK_ID, null);

        assertThat(result).isTrue();
        verify(subTaskMapper, never()).incrementAttemptTotal(anyLong(), any(OffsetDateTime.class));
        verify(subTaskService, never()).changeStatus(
                eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull(), anyMap());
    }
}