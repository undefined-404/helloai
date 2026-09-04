package com.helloai.core.review.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.quality.QualityProfileUpdater;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.review.dto.DefectDistribution;
import com.helloai.core.review.dto.QualityTrendPoint;
import com.helloai.core.review.dto.ReviewerLeniency;
import com.helloai.core.review.dto.ReworkRoundPoint;
import com.helloai.core.review.mapper.ReviewRecordMapper;
import com.helloai.core.review.mapper.ReviewRecheckLogMapper;
import com.helloai.core.review.service.impl.ReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * ReviewService（人工审查入口）单元测试：
 * 人工驳回必须走 reworkFresh 重置返工计数并清除人工介入标记，
 * 否则改派后的新执行者提交时仍命中 skip_max_rework 跳过自动核验、无节点流转。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewService")
class ReviewServiceTest {

    private static final Long SUB_TASK_ID = 1L;
    private static final Long TASK_ID = 10L;
    private static final Long REVIEWER_ID = 2L;
    private static final Long EXECUTOR_ID = 3L;
    private static final Long NEW_AGENT_ID = 4L;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private RewardService rewardService;

    @Mock
    private ReviewRecordMapper reviewRecordMapper;

    @Mock
    private AgentService agentService;

    @Mock
    private ExecutionCommandService executionCommandService;

    @Mock
    private QualityProfileUpdater qualityProfileUpdater;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ReviewRecheckLogMapper reviewRecheckLogMapper;

    @Mock
    private AgentEventRecorder agentEventRecorder;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(subTaskService, rewardService, agentService,
                executionCommandService, taskTimelineService, agentEventRecorder,
                qualityProfileUpdater, reviewRecheckLogMapper);
        // createReview 内部 round 计数与 record 落库依赖父类 baseMapper；
        // lenient：校验失败路径（如 issues 为空）在 count 之前就返回，stub 不必然被消费
        ReflectionTestUtils.setField(reviewService, "baseMapper", reviewRecordMapper);
        lenient().when(reviewRecordMapper.selectCount(any())).thenReturn(0L);
    }

    private SubTask reviewSubTask(int reworkCount, Map<String, Object> context) {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        subTask.setStatus(SubTaskStatus.REVIEW);
        subTask.setAssignedAgentId(EXECUTOR_ID);
        subTask.setReworkCount(reworkCount);
        subTask.setContext(context);
        return subTask;
    }

    private Map<String, Object> manualInterventionContext() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("manualIntervention", Map.of("reason", "rework_limit", "reworkCount", 3));
        return ctx;
    }

    @Test
    @DisplayName("§6.57: 人工驳回改派走 reworkFresh（计数归零 + 清标记），新执行者重新计数")
    void shouldResetReworkOnManualRejectWithReassign() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                1, "产出质量不达标", "人工驳回并改派", NEW_AGENT_ID);

        // 核心断言：人工驳回必须走重置链路（而非累加或原样流转）
        verify(subTaskService).reworkFresh(SUB_TASK_ID, NEW_AGENT_ID);
        verify(subTaskService, never()).complete(SUB_TASK_ID);
        // Phase 0 B2：人工驳回补发 REVIEW_REJECTED（终态投影与自动核验驳回对称）
        verify(agentEventRecorder).record(any(), eq(TASK_ID), eq(SUB_TASK_ID), eq(0), eq(0),
                eq(AgentEventType.REVIEW_REJECTED), eq(REVIEWER_ID), any());
        // 原执行者按评分扣分（与 兼容）
        verify(rewardService).addReward(eq(EXECUTOR_ID), any(), eq(-5), eq(SUB_TASK_ID));
    }

    @Test
    @DisplayName("§6.57: 人工驳回不改派（原执行者重做）同样重置计数")
    void shouldResetReworkOnManualRejectWithoutReassign() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                2, "需补充验收证据", "人工驳回重做", null);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, null);
        verify(subTaskService, never()).complete(SUB_TASK_ID);
    }

    @Test
    @DisplayName("§6.57: 人工通过不受影响，走 complete 且不触发重置")
    void shouldCompleteOnManualApprove() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.APPROVED,
                4, null, "人工直接通过", null);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).reworkFresh(anyLong(), any());
        // Phase 0 B2：人工验收补发 REVIEW_APPROVED（DONE 终态投影一致，Step 2 对账修复）
        verify(agentEventRecorder).record(any(), eq(TASK_ID), eq(SUB_TASK_ID), eq(0), eq(0),
                eq(AgentEventType.REVIEW_APPROVED), eq(REVIEWER_ID), any());
    }

    @Test
    @DisplayName("§6.100: 人工驳回改派 API_KEY_LLM 执行者时补发执行命令（内循环闭合）")
    void shouldSendExecutionCommandOnManualRejectToApiKeyAgent() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        Agent reworkAgent = new Agent();
        reworkAgent.setId(NEW_AGENT_ID);
        reworkAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        when(agentService.getById(NEW_AGENT_ID)).thenReturn(reworkAgent);
        // Phase 1 Step 1 fix：requiredSkills 由命令创建方装箱（task 域查询出口返回空列表）
        when(subTaskService.requiredSkillsOf(TASK_ID)).thenReturn(List.of());

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                1, "产出质量不达标", "人工驳回并改派", NEW_AGENT_ID);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, NEW_AGENT_ID);
        verify(executionCommandService).createAssignedCommand(SUB_TASK_ID, NEW_AGENT_ID, "manual-review-rework", List.of());
    }

    @Test
    @DisplayName("§6.100: 人工驳回不改派时对原执行者（API_KEY_LLM）补发执行命令")
    void shouldSendExecutionCommandToOriginalExecutorWhenNoReassign() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        Agent executor = new Agent();
        executor.setId(EXECUTOR_ID);
        executor.setAccessType(AgentAccessType.API_KEY_LLM);
        when(agentService.getById(EXECUTOR_ID)).thenReturn(executor);
        // Phase 1 Step 1 fix：requiredSkills 由命令创建方装箱（task 域查询出口返回空列表）
        when(subTaskService.requiredSkillsOf(TASK_ID)).thenReturn(List.of());

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                2, "需补充验收证据", "人工驳回重做", null);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, null);
        verify(executionCommandService).createAssignedCommand(SUB_TASK_ID, EXECUTOR_ID, "manual-review-rework", List.of());
    }

    @Test
    @DisplayName("§6.100: 非 API_KEY_LLM 执行者（CLI_CLIENT）不补发执行命令")
    void shouldNotSendExecutionCommandToCliAgent() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        Agent reworkAgent = new Agent();
        reworkAgent.setId(NEW_AGENT_ID);
        reworkAgent.setAccessType(AgentAccessType.CLI_CLIENT);
        when(agentService.getById(NEW_AGENT_ID)).thenReturn(reworkAgent);

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                1, "产出质量不达标", "人工驳回并改派", NEW_AGENT_ID);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, NEW_AGENT_ID);
        verify(executionCommandService, never()).createAssignedCommand(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("§6.100: 执行命令下发失败仅告警，不阻断人工驳回链路")
    void shouldNotBreakReviewWhenCommandDispatchFails() {
        SubTask subTask = reviewSubTask(3, manualInterventionContext());
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        Agent reworkAgent = new Agent();
        reworkAgent.setId(NEW_AGENT_ID);
        reworkAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        when(agentService.getById(NEW_AGENT_ID)).thenReturn(reworkAgent);
        when(subTaskService.requiredSkillsOf(TASK_ID)).thenReturn(List.of());
        when(executionCommandService.createAssignedCommand(SUB_TASK_ID, NEW_AGENT_ID, "manual-review-rework", List.of()))
                .thenThrow(new RuntimeException("MQ 不可用"));

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                1, "产出质量不达标", "人工驳回并改派", NEW_AGENT_ID);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, NEW_AGENT_ID);
        verify(rewardService).addReward(eq(EXECUTOR_ID), any(), eq(-5), eq(SUB_TASK_ID));
    }

    @Test
    @DisplayName("§6.57: 驳回必须填写问题描述，否则抛 BizException")
    void shouldRejectBlankIssues() {
        // issues 校验在 getById 之前抛出，无需 stub 子任务
        assertThatThrownBy(() -> reviewService.createReview(SUB_TASK_ID, REVIEWER_ID,
                ReviewResult.REJECTED, 1, "  ", null, NEW_AGENT_ID))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("§6.147: 质量趋势源透传 Mapper，days<=0 归一 30 天")
    void shouldPassthroughTrendSourceWithNormalizedDays() {
        List<QualityTrendPoint> points = List.of(
                new QualityTrendPoint("2026-08-20", 3, 2, 3.7),
                new QualityTrendPoint("2026-08-21", 5, 4, 4.0));
        when(reviewRecordMapper.selectTrendSource(30)).thenReturn(points);

        assertThat(reviewService.statsTrendSource(0)).isEqualTo(points);
        verify(reviewRecordMapper).selectTrendSource(30);

        // 窗口正常传参直接透传
        List<QualityTrendPoint> empty = new ArrayList<>();
        when(reviewRecordMapper.selectTrendSource(7)).thenReturn(empty);
        assertThat(reviewService.statsTrendSource(7)).isEmpty();
    }

    @Test
    @DisplayName("§6.147: 驳回原因分布复用 DefectLabelParser 聚合，按计数降序")
    void shouldAggregateDefectDistribution() {
        // issues 四元组真实格式：[defect] 描述 [location] 位置 [impact] 影响 [evidence] 依据
        when(reviewRecordMapper.selectIssuesForStats(30)).thenReturn(List.of(
                "产出有误 [defect] 规格缺失 [location] 模块A [impact] 高 [evidence] 复现步骤",
                "[defect] 规格缺失 [location] 模块A [impact] 高 [evidence] 复现步骤" +
                        " [defect] 实现偏差 [location] 模块B [impact] 中 [evidence] 日志",
                "无可复现证据"));

        List<DefectDistribution> result = reviewService.statsDefectDistribution(0);

        assertThat(result).hasSize(2);
        // 降序：规格缺失 2 次 > 实现偏差 1 次
        assertThat(result.get(0).defectTag()).isEqualTo("规格缺失");
        assertThat(result.get(0).count()).isEqualTo(2);
        assertThat(result.get(1).defectTag()).isEqualTo("实现偏差");
        assertThat(result.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("§6.147: 返工轮次分布透传 Mapper（round 升序由 SQL 保证）")
    void shouldPassthroughReworkDistribution() {
        List<ReworkRoundPoint> points = List.of(
                new ReworkRoundPoint(1, 12),
                new ReworkRoundPoint(2, 5));
        when(reviewRecordMapper.selectReworkDistribution(30)).thenReturn(points);

        assertThat(reviewService.statsReworkDistribution(-1)).isEqualTo(points);
        verify(reviewRecordMapper).selectReworkDistribution(30);
    }

    @Test
    @DisplayName("§6.147: Reviewer 放水率经 AgentService 批量补名，缺失显示 ID 字符串")
    void shouldFillReviewerNameViaAgentService() {
        List<ReviewerLeniency> rows = List.of(
                new ReviewerLeniency(2L, "", 5, 80, 4.2),
                new ReviewerLeniency(9L, "", 3, 33, 3.0));
        when(reviewRecordMapper.selectReviewerLeniency(30)).thenReturn(rows);
        Agent reviewer = new Agent();
        reviewer.setId(2L);
        reviewer.setName("审查者A");
        when(agentService.listByIds(List.of(2L, 9L))).thenReturn(List.of(reviewer));

        List<ReviewerLeniency> result = reviewService.statsReviewerLeniency(30);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).reviewerName()).isEqualTo("审查者A");
        // 缺失（9L 不在 agent 表）回退 ID 字符串，其余字段保持 SQL 投影值
        assertThat(result.get(1).reviewerName()).isEqualTo("9");
        assertThat(result.get(1).approveRate()).isEqualTo(33);
    }
}
