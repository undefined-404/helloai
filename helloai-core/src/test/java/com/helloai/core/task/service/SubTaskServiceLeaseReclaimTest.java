package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.WatchdogProperties;
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
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租约回收 + 执行会话中断点记录（Phase 1 Step 3，N-007 恢复载体）：
 * reclaimExpiredLeases 回收过期租约时，调用 agentSessionService.interrupt 记录中断点
 * （ABORT 幂等防重入）并落 timeline sub_task_session_interrupted。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskService 租约回收 + Session 中断点")
class SubTaskServiceLeaseReclaimTest {

    private static final long SUB_TASK_ID = 22L;
    private static final long TASK_ID = 33L;
    private static final long AGENT_ID = 11L;

    private final LambdaQueryChainWrapper<SubTask> queryChain = mock(LambdaQueryChainWrapper.class);

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
        subTaskService = org.mockito.Mockito.spy(new SubTaskServiceImpl(
                agentOutboxService, agentInboxService, agentServiceProvider,
                heartbeatService, reviewPort, implicitScoreCalculator,
                rewardService, applicationEventPublisher, taskTimelineService,
                new AgentDispatchProperties(), concurrencyQuotaService,
                new WatchdogProperties(), agentSessionService,
                attachmentServiceProvider, taskServiceProvider, agentEventRecorder,
                subTaskMapper));
        // lambdaQuery 链式 mock：绕开无 Spring 上下文时的 baseMapper 依赖
        lenient().doReturn(queryChain).when(subTaskService).lambdaQuery();
        lenient().when(queryChain.eq(any(), any())).thenReturn(queryChain);
        lenient().when(queryChain.isNotNull(any())).thenReturn(queryChain);
        lenient().when(queryChain.lt(any(), any())).thenReturn(queryChain);
        lenient().when(queryChain.orderByAsc(any(SFunction.class))).thenReturn(queryChain);
        lenient().when(queryChain.last(anyString())).thenReturn(queryChain);
    }

    @Test
    @DisplayName("回收过期租约：记录 session 中断点 timeline（sub_task_session_interrupted）+ ABORT 幂等")
    void shouldRecordSessionInterruptedOnReclaim() {
        SubTask expired = new SubTask();
        expired.setId(SUB_TASK_ID);
        expired.setTaskId(TASK_ID);
        expired.setStatus(SubTaskStatus.IN_PROGRESS);
        expired.setOwner("node-1");
        expired.setLeaseUntil(OffsetDateTime.now().minusMinutes(5));
        when(queryChain.list()).thenReturn(List.of(expired));

        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
        AgentSessionService.InterruptedSession interrupted =
                new AgentSessionService.InterruptedSession(7L, AGENT_ID, 3, 2, Map.of("depCount", 1));
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
        SubTask expired = new SubTask();
        expired.setId(SUB_TASK_ID);
        expired.setTaskId(TASK_ID);
        expired.setStatus(SubTaskStatus.IN_PROGRESS);
        expired.setOwner("node-1");
        expired.setLeaseUntil(OffsetDateTime.now().minusMinutes(5));
        when(queryChain.list()).thenReturn(List.of(expired));

        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
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
