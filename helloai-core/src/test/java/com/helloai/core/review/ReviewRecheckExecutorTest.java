package com.helloai.core.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewExecutionEngine;
import com.helloai.core.review.support.ReviewRecheckExecutor;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewRecheckExecutor 单元测试（§7.8 拆分：抽检复审自 SubTaskReviewServiceImpl 迁出）。
 *
 * <p>覆盖：一致（discrepancy=false）/ 放水（discrepancy=true）/ 跳过三类；
 * 抽检只度量不改状态（不 complete/rework、不落 review_record）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewRecheckExecutor")
class ReviewRecheckExecutorTest {

    private static final Long SUB_TASK_ID = 22L;
    private static final Long TASK_ID = 10L;
    private static final Long RECORD_ID = 999L;

    @Mock
    private ReviewService recordReviewService;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private ReviewerPicker reviewerPicker;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private AgentQualityProfileService agentQualityProfileService;
    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private AgentDispatchProperties dispatchProperties;

    private ReviewRecheckExecutor executor;

    @BeforeEach
    void setUp() {
        // 判定复用真实执行引擎（与单审/双审同一执行口径），底层仅 mock LLM 与持久化
        ReviewExecutionEngine engine = new ReviewExecutionEngine(
                platformAgentExecutionService, conversationService, taskTimelineService,
                new VerdictParser(new ObjectMapper()),
                new ReviewEvidenceAssembler(attachmentService, dispatchProperties));
        executor = new ReviewRecheckExecutor(recordReviewService, subTaskService,
                reviewerPicker, taskTimelineService, agentQualityProfileService, engine,
                conversationService);
        lenient().when(reviewerPicker.pickSingle(any())).thenReturn(reviewer(9L));
    }

    private ReviewRecord approvedRecord(Long id) {
        ReviewRecord record = new ReviewRecord();
        record.setId(id);
        record.setSubTaskId(SUB_TASK_ID);
        record.setReviewerAgentId(5L);
        record.setResult(ReviewResult.APPROVED);
        record.setScore(5);
        return record;
    }

    private SubTask reviewSubTask() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        subTask.setStatus(SubTaskStatus.REVIEW);
        subTask.setTitle("写接口文档");
        subTask.setContent("整理 REST 接口清单");
        subTask.setDeliverable("接口文档");
        subTask.setAcceptance("覆盖全部端点");
        subTask.setReworkCount(0);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        subTask.setContext(ctx);
        return subTask;
    }

    private Agent reviewer(long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setRole(AgentRole.REVIEWER);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        return agent;
    }

    @Test
    @DisplayName("§6.142 抽检复审一致 → 落 recheck log（discrepancy=false）+ reviewer 画像 +1")
    void shouldRecheckRecordWhenConsistent() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(recordReviewService).recordRecheck(
                eq(RECORD_ID), eq(SUB_TASK_ID), eq(ReviewResult.APPROVED), eq(ReviewResult.APPROVED),
                eq(false), eq(9L), any(), any(), any());
        verify(agentQualityProfileService).incrementReviewerStats(9L, 1, 0);
        verify(conversationService).addMessage(
                eq(SUB_TASK_ID), eq(9L), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_recheck_result"));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_recheck_consistent"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
        // 抽检不改状态、不落 review_record
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(recordReviewService, never()).recordAutoReview(anyLong(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("§6.142 抽检发现放水（原 APPROVED 复审 REJECTED）→ discrepancy=true + timeline 观测")
    void shouldRecheckRecordWhenDiscrepancy() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 1, \"issues\": \"产出为编造\", \"comment\": \"\"}", "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(recordReviewService).recordRecheck(
                eq(RECORD_ID), eq(SUB_TASK_ID), eq(ReviewResult.APPROVED), eq(ReviewResult.REJECTED),
                eq(true), eq(9L), any(), any(), any());
        verify(conversationService).addMessage(
                eq(SUB_TASK_ID), eq(9L), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_recheck_result"));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_recheck_discrepancy"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
    }

    @Test
    @DisplayName("§6.142 抽检跳过：记录不存在 / 非 APPROVED / 无可用 reviewer 均不调 LLM")
    void shouldSkipRecheckWhenNotEligible() {
        // 记录不存在
        when(recordReviewService.getById(RECORD_ID)).thenReturn(null);
        executor.recheckReviewRecord(RECORD_ID);
        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));

        // 非 APPROVED（人工驳回记录不属于抽检目标）
        ReviewRecord rejected = approvedRecord(RECORD_ID);
        rejected.setResult(ReviewResult.REJECTED);
        when(recordReviewService.getById(RECORD_ID)).thenReturn(rejected);
        executor.recheckReviewRecord(RECORD_ID);
        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));

        // 无可用 reviewer → 跳过
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(reviewerPicker.pickSingle(any())).thenReturn(null);
        executor.recheckReviewRecord(RECORD_ID);
        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
    }
}
