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
import com.helloai.core.agent.service.RewardService;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.impl.SubTaskLeaseServiceImpl;
import com.helloai.core.task.service.impl.SubTaskNotificationServiceImpl;
import com.helloai.core.task.service.impl.SubTaskQueryServiceImpl;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import com.helloai.core.task.service.impl.SubTaskStateMachineServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租约回收 + 执行会话中断点记录（N-007 恢复载体）：
 * reclaimExpiredLeases 回收过期租约时，调用 agentSessionService.interrupt 记录中断点
 * （ABORT 幂等防重入）并落 timeline sub_task_session_interrupted。
 *
 * <p><b>RM9-b 装配口径变更（断言逐条未改）</b>：回收链已迁入 {@link SubTaskLeaseService}
 * （状态写入半段下沉 {@link SubTaskStateMachineService#reclaimToPending}），
 * 原先 spy 门面拦截 {@code lambdaQuery()} 链的做法改为直接对同域 {@code SubTaskMapper} 设桩；
 * 断言（interrupt 调用 / timeline 事件有无）与拆分前逐字一致。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskService 租约回收 + Session 中断点")
class SubTaskServiceLeaseReclaimTest {

    private static final long SUB_TASK_ID = 22L;
    private static final long TASK_ID = 33L;
    private static final long AGENT_ID = 11L;

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
    @Mock private ObjectProvider<com.helloai.core.task.service.AttachmentService> attachmentServiceProvider;
    @Mock private ObjectProvider<com.helloai.core.task.service.TaskService> taskServiceProvider;
    @Mock private AgentEventRecorder agentEventRecorder;
    @Mock private SubTaskMapper subTaskMapper;
    @Mock private AgentSessionService agentSessionService;

    private SubTaskServiceImpl subTaskService;

    @BeforeEach
    void setUp() {
        SubTaskQueryService queryService = new SubTaskQueryServiceImpl(
                subTaskMapper, taskServiceProvider, reviewPort);
        SubTaskNotificationService notificationService = new SubTaskNotificationServiceImpl(
                agentInboxService, agentServiceProvider, applicationEventPublisher, reviewPort, queryService);
        SubTaskStateMachineService stateMachineService = new SubTaskStateMachineServiceImpl(
                subTaskMapper, notificationService, agentOutboxService, heartbeatService,
                concurrencyQuotaService, new AgentDispatchProperties(), new WatchdogProperties(),
                taskTimelineService, agentServiceProvider, attachmentServiceProvider,
                agentEventRecorder, implicitScoreCalculator, rewardService, reviewPort,
                applicationEventPublisher);
        SubTaskLeaseService leaseService = new SubTaskLeaseServiceImpl(
                subTaskMapper, taskTimelineService, agentSessionService, stateMachineService);
        subTaskService = new SubTaskServiceImpl(
                subTaskMapper, queryService, stateMachineService, leaseService);
    }

    private SubTask expiredSubTask() {
        SubTask expired = new SubTask();
        expired.setId(SUB_TASK_ID);
        expired.setTaskId(TASK_ID);
        expired.setStatus(SubTaskStatus.IN_PROGRESS);
        expired.setOwner("node-1");
        expired.setLeaseUntil(OffsetDateTime.now().minusMinutes(5));
        return expired;
    }

    @Test
    @DisplayName("回收过期租约：记录 session 中断点 timeline（sub_task_session_interrupted）+ ABORT 幂等")
    void shouldRecordSessionInterruptedOnReclaim() {
        when(subTaskMapper.selectList(any())).thenReturn(List.of(expiredSubTask()));
        when(subTaskMapper.updateById(any(SubTask.class))).thenReturn(1);
        AgentSessionService.InterruptedSession interrupted =
                new AgentSessionService.InterruptedSession(7L, AGENT_ID, 3, 2, "ABORTED", null, Map.of("depCount", 1));
        when(agentSessionService.interrupt(SUB_TASK_ID)).thenReturn(interrupted);

        subTaskService.reclaimExpiredLeases(10);

        // 状态回收 + 中断点记录（N-007 恢复载体）
        verify(agentSessionService).interrupt(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                org.mockito.ArgumentMatchers.eq(TASK_ID),
                org.mockito.ArgumentMatchers.eq(SUB_TASK_ID),
                org.mockito.ArgumentMatchers.eq("sub_task_session_interrupted"),
                org.mockito.ArgumentMatchers.eq(AgentRole.SYSTEM),
                org.mockito.ArgumentMatchers.eq(AGENT_ID),
                anyMap());
    }

    @Test
    @DisplayName("回收时无 ACTIVE 会话：interrupt 返回 null → 不落中断点 timeline")
    void shouldSkipInterruptTimelineWhenNoSession() {
        when(subTaskMapper.selectList(any())).thenReturn(List.of(expiredSubTask()));
        when(subTaskMapper.updateById(any(SubTask.class))).thenReturn(1);
        when(agentSessionService.interrupt(SUB_TASK_ID)).thenReturn(null);

        subTaskService.reclaimExpiredLeases(10);

        verify(agentSessionService).interrupt(SUB_TASK_ID);
        verify(taskTimelineService, org.mockito.Mockito.never()).recordEvent(
                org.mockito.ArgumentMatchers.eq(TASK_ID),
                org.mockito.ArgumentMatchers.eq(SUB_TASK_ID),
                org.mockito.ArgumentMatchers.eq("sub_task_session_interrupted"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                anyMap());
    }
}
