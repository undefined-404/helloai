package com.helloai.core.task.service;

import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.service.impl.SubTaskServiceImpl;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.service.AttachmentService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 执行者变更撤销通知单元测试：
 * 换人改派补发 sub_task.reassigned / 回收补发 sub_task.unassigned / 初始分配与原地保留不通知。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskService 执行者变更撤销通知")
class SubTaskServiceHandoverTest {

    private static final long SUB_TASK_ID = 1L;
    private static final long OLD_AGENT = 100L;
    private static final long NEW_AGENT = 200L;

    @Mock private AgentInboxService agentInboxService;
    @Mock private AgentOutboxService agentOutboxService;
    @Mock private ObjectProvider<AgentService> agentServiceProvider;
    @Mock private HeartbeatService heartbeatService;
    @Mock private ReviewRecordMapper reviewRecordMapper;
    @Mock private ImplicitScoreCalculator implicitScoreCalculator;
    @Mock private RewardService rewardService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private ConcurrencyQuotaService concurrencyQuotaService;
    @Mock private AttachmentService attachmentService;
    @Mock private ObjectProvider<AttachmentService> attachmentServiceProvider;

    private SubTaskService subTaskService;

    @BeforeEach
    void setUp() {
        AgentDispatchProperties dispatchProps = new AgentDispatchProperties();
        dispatchProps.setEnforceMaxConcurrent(true);
        subTaskService = spy(new SubTaskServiceImpl(
                agentOutboxService, agentInboxService, agentServiceProvider,
                heartbeatService, reviewRecordMapper, implicitScoreCalculator,
                rewardService, applicationEventPublisher, taskTimelineService,
                dispatchProps, concurrencyQuotaService, attachmentServiceProvider));
        doReturn(true).when(subTaskService).updateById(any(SubTask.class));
        // §6.104 打回失效：让 ObjectProvider 返回 mock，便于断言 invalidateBySubTask 被调
        //（类级 @MockitoSettings(strictness = Strictness.LENIENT) 已开启，无需 Mockito.lenient()）
        when(attachmentServiceProvider.getIfAvailable()).thenReturn(attachmentService);
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
        // §6.104 打回失效：reworkFresh 触发附件 ACTIVE → INACTIVE
        verify(attachmentService).invalidateBySubTask(SUB_TASK_ID);
    }

    @Test
    @DisplayName("人工驳回不换派（reworkFresh 保持原执行者）：仅补发 sub_task.rejected 返工通知")
    void shouldNotifyReworkWhenReworkFreshKeepsAgent() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.reworkFresh(SUB_TASK_ID, null);

        //  撤销通知：执行者未变不触发
        verify(agentInboxService, never()).send(
                any(), any(), eq("sub_task.reassigned"), any(), any(), any(), any(), any());
        verify(agentInboxService, never()).send(
                any(), any(), eq("sub_task.unassigned"), any(), any(), any(), any(), any());
        //  返工通知：保持原执行者时发给原执行者
        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.rejected"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), eq("HIGH"));
    }

    @Test
    @DisplayName("人工驳回不换派：无 review 历史时摘要回退默认文案")
    void shouldFallbackSummaryWhenNoReviewHistory() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.reworkFresh(SUB_TASK_ID, null);

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.rejected"),
                anyString(), summaryCaptor.capture(), eq("sub_task"), eq(SUB_TASK_ID), eq("HIGH"));
        assertThat(summaryCaptor.getValue()).contains("请查审查记录");
    }

    @Test
    @DisplayName("驳回补发通知：从 context.reviewHistory 最新一轮提取评分/评语/问题摘要")
    void shouldExtractReworkSummaryFromReviewHistory() {
        SubTask reviewTask = subTask(SubTaskStatus.REVIEW, OLD_AGENT);
        reviewTask.setContext(Map.of("reviewHistory", List.of(
                Map.of("round", 1, "score", 2, "comment", "实现不完整",
                        "issues", List.of("缺异常处理", "无单测"))
        )));
        doReturn(reviewTask).when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.rework(SUB_TASK_ID, null);

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.rejected"),
                anyString(), summaryCaptor.capture(), eq("sub_task"), eq(SUB_TASK_ID), eq("HIGH"));
        String summary = summaryCaptor.getValue();
        assertThat(summary).contains("审查未通过")
                .contains("评分 2/5")
                .contains("实现不完整")
                .contains("缺异常处理");
    }

    @Test
    @DisplayName("自动驳回换人（rework 防御性兼容）：旧执行者收到 sub_task.reassigned，新执行者收到 sub_task.rejected")
    void shouldNotifyOldAgentWhenReworkSwitchesAgent() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);

        subTaskService.rework(SUB_TASK_ID, NEW_AGENT);

        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.reassigned"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), anyString());
        // 返工通知发给改派后的新执行者（reworkAgentId 非空时）
        verify(agentInboxService).send(eq(NEW_AGENT), anyString(), eq("sub_task.rejected"),
                anyString(), anyString(), eq("sub_task"), eq(SUB_TASK_ID), eq("HIGH"));
    }

    // ══════════════════════════════════════════════════════════════
    //  complete：审查通过补发 sub_task.approved
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("审查通过（complete）：向执行者补发 sub_task.approved，摘要取 review_record 最新一轮评分/评语")
    void shouldNotifyApprovedOnComplete() {
        SubTask done = subTask(SubTaskStatus.REVIEW, OLD_AGENT);
        doReturn(done).when(subTaskService).getById(SUB_TASK_ID);
        stubImplicitScore();
        ReviewRecord latest = new ReviewRecord();
        latest.setSubTaskId(SUB_TASK_ID);
        latest.setRound(2);
        latest.setScore(5);
        latest.setComment("质量优秀");
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of(latest));

        subTaskService.complete(SUB_TASK_ID);

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.approved"),
                anyString(), summaryCaptor.capture(), eq("sub_task"), eq(SUB_TASK_ID), eq("NORMAL"));
        assertThat(summaryCaptor.getValue()).contains("审查通过")
                .contains("评分 5/5")
                .contains("质量优秀");
    }

    @Test
    @DisplayName("审查通过（complete）：无 review_record 时摘要回退默认文案")
    void shouldFallbackApprovedSummaryWhenNoReview() {
        doReturn(subTask(SubTaskStatus.REVIEW, OLD_AGENT))
                .when(subTaskService).getById(SUB_TASK_ID);
        stubImplicitScore();
        when(reviewRecordMapper.selectList(any())).thenReturn(List.of());

        subTaskService.complete(SUB_TASK_ID);

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentInboxService).send(eq(OLD_AGENT), anyString(), eq("sub_task.approved"),
                anyString(), summaryCaptor.capture(), eq("sub_task"), eq(SUB_TASK_ID), eq("NORMAL"));
        assertThat(summaryCaptor.getValue()).contains("审查通过，请查看详情");
    }

    /** 桩掉 complete() 内隐式评分（避免 mock 返回 null 触发 NPE 打 ERROR 日志）。 */
    private void stubImplicitScore() {
        when(implicitScoreCalculator.calculate(any(), any(), anyInt(), anyInt())).thenReturn(
                ImplicitScoreCalculator.ScoreResult.builder()
                        .factors(new ImplicitScoreCalculator.ScoreFactors())
                        .compositeScore(80)
                        .grade("B")
                        .rewardDelta(0)
                        .build());
    }
}
