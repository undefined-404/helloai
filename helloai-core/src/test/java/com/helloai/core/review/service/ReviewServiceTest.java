package com.helloai.core.review.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.quality.QualityProfileUpdater;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.RewardService;
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

import java.util.HashMap;
import java.util.Map;

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
    private ReviewRecheckLogMapper reviewRecheckLogMapper;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(subTaskService, rewardService, agentService,
                executionCommandService, qualityProfileUpdater, reviewRecheckLogMapper);
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

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                1, "产出质量不达标", "人工驳回并改派", NEW_AGENT_ID);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, NEW_AGENT_ID);
        verify(executionCommandService).createAssignedCommand(SUB_TASK_ID, NEW_AGENT_ID, "manual-review-rework");
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

        reviewService.createReview(SUB_TASK_ID, REVIEWER_ID, ReviewResult.REJECTED,
                2, "需补充验收证据", "人工驳回重做", null);

        verify(subTaskService).reworkFresh(SUB_TASK_ID, null);
        verify(executionCommandService).createAssignedCommand(SUB_TASK_ID, EXECUTOR_ID, "manual-review-rework");
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
        verify(executionCommandService, never()).createAssignedCommand(anyLong(), any(), any());
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
        when(executionCommandService.createAssignedCommand(SUB_TASK_ID, NEW_AGENT_ID, "manual-review-rework"))
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
}
