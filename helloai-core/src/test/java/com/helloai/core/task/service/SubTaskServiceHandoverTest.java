package com.helloai.core.task.service;

import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.common.constant.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * A0-1（§6.60）执行者变更撤销通知单元测试：
 * 换人改派补发 sub_task.reassigned / 回收补发 sub_task.unassigned / 初始分配与原地保留不通知。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskService 执行者变更撤销通知（A0-1）")
class SubTaskServiceHandoverTest {

    private static final long SUB_TASK_ID = 1L;
    private static final long OLD_AGENT = 100L;
    private static final long NEW_AGENT = 200L;

    @Mock private AgentInboxService agentInboxService;
    @Mock private AgentOutboxService agentOutboxService;
    @Mock private AgentService agentService;
    @Mock private HeartbeatService heartbeatService;
    @Mock private ReviewRecordMapper reviewRecordMapper;
    @Mock private ImplicitScoreCalculator implicitScoreCalculator;
    @Mock private RewardService rewardService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TaskTimelineService taskTimelineService;

    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        subTaskService = spy(new SubTaskService(
                agentOutboxService, agentInboxService, agentService,
                heartbeatService, reviewRecordMapper, implicitScoreCalculator,
                rewardService, applicationEventPublisher, taskTimelineService));
        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
    }

    private SubTask subTask(SubTaskStatus status, Long assignedAgentId) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTitle("测试任务");
        subTask.setStatus(status);
        subTask.setAssignedAgentId(assignedAgentId);
        return subTask;
    }

    private List<String> capturedEventTypes() {
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentInboxService, times(1)).send(
                anyLong(), anyString(), eventTypeCaptor.capture(),
                anyString(), anyString(), anyString(), anyLong(), anyString());
        return eventTypeCaptor.getAllValues();
    }

    // ══════════════════════════════════════════════════════════════
    //  changeStatus：换人 / 回收 / 初始分配
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("改派双通知：旧执行者收到 sub_task.unassigned，新执行者收到 sub_task.assigned")
    void shouldNotifyOldAgentWhenReassigned() {
        doReturn(subTask(SubTaskStatus.ASSIGNED, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        // 真实改派链路：第一步回收（ASSIGNED → PENDING，清空执行者），第二步重新分配（PENDING → ASSIGNED）
        subTaskService.changeStatus(SUB_TASK_ID, SubTaskStatus.PENDING, null);
        subTaskService.changeStatus(SUB_TASK_ID, SubTaskStatus.ASSIGNED, NEW_AGENT);

        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.unassigned"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), anyString());
        verify(agentInboxService).send(eq(NEW_AGENT), anyString(), eq("sub_task.assigned"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), anyString());
    }

    @Test
    @DisplayName("执行者回收：旧执行者收到 sub_task.unassigned（回 PENDING 清空执行者）")
    void shouldNotifyOldAgentWhenUnassigned() {
        doReturn(subTask(SubTaskStatus.ASSIGNED, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.changeStatus(SUB_TASK_ID, SubTaskStatus.PENDING, null);

        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.unassigned"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), anyString());
    }

    @Test
    @DisplayName("初始分配：无旧执行者，不发撤销通知（仅新执行者收到 assigned）")
    void shouldNotNotifyOnInitialAssign() {
        doReturn(subTask(SubTaskStatus.PENDING, null))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.changeStatus(SUB_TASK_ID, SubTaskStatus.ASSIGNED, NEW_AGENT);

        assertThat(capturedEventTypes()).containsExactly("sub_task.assigned");
    }

    @Test
    @DisplayName("同一执行者原地保留：不发撤销通知")
    void shouldNotNotifyWhenSameAgentKept() {
        doReturn(subTask(SubTaskStatus.REWORK, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        // REWORK → IN_PROGRESS 为合法转换，执行者保持 OLD_AGENT 不变
        subTaskService.changeStatus(SUB_TASK_ID, SubTaskStatus.IN_PROGRESS, OLD_AGENT);

        verify(agentInboxService, never()).send(any(), any(), eq("sub_task.reassigned"),
                any(), any(), any(), any(), any());
        verify(agentInboxService, never()).send(any(), any(), eq("sub_task.unassigned"),
                any(), any(), any(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════
    //  reworkFresh（人工驳回换派） / rework（自动驳回防御性兼容）
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("人工驳回换派（reworkFresh）：旧执行者收到 sub_task.reassigned")
    void shouldNotifyOldAgentWhenReworkFreshSwitchesAgent() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.reworkFresh(SUB_TASK_ID, NEW_AGENT);

        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.reassigned"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), anyString());
    }

    @Test
    @DisplayName("人工驳回不换派（reworkFresh 保持原执行者）：不发任何通知")
    void shouldNotNotifyWhenReworkFreshKeepsAgent() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.reworkFresh(SUB_TASK_ID, null);

        verify(agentInboxService, never()).send(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("自动驳回换人（rework 防御性兼容）：旧执行者收到 sub_task.reassigned")
    void shouldNotifyOldAgentWhenReworkSwitchesAgent() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.rework(SUB_TASK_ID, NEW_AGENT);

        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.reassigned"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), anyString());
    }
}
