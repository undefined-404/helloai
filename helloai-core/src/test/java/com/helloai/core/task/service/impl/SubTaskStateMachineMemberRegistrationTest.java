package com.helloai.core.task.service.impl;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.WatchdogProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskMemberJoinSource;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.RewardService;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskNotificationService;
import com.helloai.core.task.service.TaskAgentMemberService;
import com.helloai.core.task.service.TaskTimelineService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * G-031：Task-Team 成员表的<b>运行期实时写入</b>单测。
 *
 * <p><b>为什么需要这组断言</b>：此前 {@code task_agent_member} 只在启动重建时写入，
 * 运行期没有入队点——重建后新建/派发的任务在下次重启前详情页无成员。修复把登记挂在
 * 状态机（归属写入的唯一入口），本类逐条钉死四条路径与「不换人不写」的负例，
 * 防止后续重构把钩子搬回分散的派发入口后再次静默缺行。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("子任务状态机 · 成员表实时入队（G-031）")
class SubTaskStateMachineMemberRegistrationTest {

    private static final long SUB_TASK_ID = 1L;
    private static final long TASK_ID = 9L;
    private static final long AGENT_ID = 100L;

    @Mock private SubTaskMapper subTaskMapper;
    @Mock private SubTaskNotificationService notificationService;
    @Mock private AgentOutboxService agentOutboxService;
    @Mock private HeartbeatService heartbeatService;
    @Mock private ConcurrencyQuotaService concurrencyQuotaService;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private ObjectProvider<AgentService> agentServiceProvider;
    @Mock private ObjectProvider<AttachmentService> attachmentServiceProvider;
    @Mock private AgentEventRecorder agentEventRecorder;
    @Mock private ImplicitScoreCalculator implicitScoreCalculator;
    @Mock private RewardService rewardService;
    @Mock private ReviewPort reviewPort;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private ObjectProvider<AgentExecutionRecordService> agentExecutionRecordServiceProvider;
    @Mock private TaskAgentMemberService taskAgentMemberService;

    private SubTaskStateMachineServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubTaskStateMachineServiceImpl(
                subTaskMapper, notificationService, agentOutboxService, heartbeatService,
                concurrencyQuotaService, new AgentDispatchProperties(), new WatchdogProperties(),
                taskTimelineService, agentServiceProvider, attachmentServiceProvider,
                agentEventRecorder, implicitScoreCalculator, rewardService, reviewPort,
                applicationEventPublisher, agentExecutionRecordServiceProvider,
                taskAgentMemberService);
        when(subTaskMapper.updateById(any(SubTask.class))).thenReturn(1);
    }

    @Test
    @DisplayName("平台派发（PENDING→ASSIGNED）-> 同事务写 ASSIGNED 成员")
    void assignShouldRegisterMember() {
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask(SubTaskStatus.PENDING));

        service.changeStatus(SUB_TASK_ID, SubTaskStatus.ASSIGNED, AGENT_ID);

        verify(taskAgentMemberService).register(TASK_ID, AGENT_ID, TaskMemberJoinSource.ASSIGNED);
    }

    @Test
    @DisplayName("外部 Agent 原子认领成功 -> 写 CLAIMED 成员")
    void claimAtomicShouldRegisterMemberAsClaimed() {
        when(subTaskMapper.claimAtomic(SUB_TASK_ID, AGENT_ID)).thenReturn(1);
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask(SubTaskStatus.ASSIGNED));

        assertThat(service.claimAtomic(SUB_TASK_ID, AGENT_ID)).isTrue();

        verify(taskAgentMemberService).register(TASK_ID, AGENT_ID, TaskMemberJoinSource.CLAIMED);
    }

    @Test
    @DisplayName("原子认领失败（被抢占/状态非法）-> 不写成员")
    void failedClaimShouldNotRegisterMember() {
        when(subTaskMapper.claimAtomic(SUB_TASK_ID, AGENT_ID)).thenReturn(0);

        assertThat(service.claimAtomic(SUB_TASK_ID, AGENT_ID)).isFalse();

        verifyNoInteractions(taskAgentMemberService);
    }

    @Test
    @DisplayName("改派（BLOCKED→PENDING→ASSIGNED）-> 写 REASSIGNED 成员")
    void reassignShouldRegisterMemberAsReassigned() {
        when(subTaskMapper.selectById(SUB_TASK_ID))
                .thenReturn(subTask(SubTaskStatus.BLOCKED), subTask(SubTaskStatus.PENDING));

        service.reassign(SUB_TASK_ID, AGENT_ID);

        verify(taskAgentMemberService).register(TASK_ID, AGENT_ID, TaskMemberJoinSource.REASSIGNED);
    }

    @Test
    @DisplayName("返工换人 -> 写 REASSIGNED 成员")
    void reworkWithNewAgentShouldRegisterMember() {
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask(SubTaskStatus.REVIEW));
        when(attachmentServiceProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.rework(SUB_TASK_ID, AGENT_ID)).isTrue();

        verify(taskAgentMemberService).register(TASK_ID, AGENT_ID, TaskMemberJoinSource.REASSIGNED);
    }

    @Test
    @DisplayName("返工不换人（reworkAgentId=null）-> 不新增成员行")
    void reworkWithoutNewAgentShouldNotRegisterMember() {
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(subTask(SubTaskStatus.REVIEW));
        when(attachmentServiceProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.rework(SUB_TASK_ID, null)).isTrue();

        verify(taskAgentMemberService, never()).register(any(), any(), any());
    }

    @Test
    @DisplayName("无换人的状态迁移（ASSIGNED→IN_PROGRESS）-> 不写成员")
    void statusChangeWithoutAgentShouldNotRegisterMember() {
        SubTask assigned = subTask(SubTaskStatus.ASSIGNED);
        assigned.setAssignedAgentId(AGENT_ID);
        when(subTaskMapper.selectById(SUB_TASK_ID)).thenReturn(assigned);

        service.changeStatus(SUB_TASK_ID, SubTaskStatus.IN_PROGRESS, null);

        verifyNoInteractions(taskAgentMemberService);
    }

    private static SubTask subTask(SubTaskStatus status) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        subTask.setStatus(status);
        subTask.setAttemptTotal(0);
        subTask.setReworkCount(0);
        return subTask;
    }
}