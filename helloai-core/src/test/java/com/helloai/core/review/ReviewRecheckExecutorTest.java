package com.helloai.core.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewExecutionEngine;
import com.helloai.core.review.support.ReviewRecheckExecutor;
import com.helloai.core.review.support.ReviewSnapshotBuilder;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        ReviewEvidenceAssembler evidenceAssembler =
                new ReviewEvidenceAssembler(attachmentService, dispatchProperties);
        ReviewExecutionEngine engine = new ReviewExecutionEngine(
                platformAgentExecutionService, conversationService, taskTimelineService,
                new VerdictParser(new ObjectMapper()),
                evidenceAssembler,
                subTaskService, new ReviewProperties());
        executor = new ReviewRecheckExecutor(recordReviewService, subTaskService,
                reviewerPicker, taskTimelineService, agentQualityProfileService, engine,
                conversationService, evidenceAssembler);
        lenient().when(reviewerPicker.pickSingle(any(), any())).thenReturn(reviewer(9L));
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

    private ReviewRecord rejectedRecord(Long id) {
        ReviewRecord record = approvedRecord(id);
        record.setResult(ReviewResult.REJECTED);
        record.setScore(1);
        return record;
    }

    private SubTaskView reviewSubTask() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        return new SubTaskView(SUB_TASK_ID, TASK_ID, SubTaskStatus.REVIEW, null, 0,
                "写接口文档", "整理 REST 接口清单", "接口文档", "覆盖全部端点", null, ctx, List.of());
    }

    private AgentProfileSnapshot reviewer(long id) {
        return AgentProfileSnapshot.builder()
                .id(id)
                .role(AgentRole.REVIEWER)
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();
    }

    @Test
    @DisplayName("§6.142 抽检复审一致 → 落 recheck log（discrepancy=false）+ reviewer 画像 +1")
    void shouldRecheckRecordWhenConsistent() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        // coverage 必填（RV-3）：通过判定不声明覆盖清单即判不可采信；
                        // 本例度量的是「复审一致落 log」，故给出合格覆盖声明
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\", "
                                + "\"analysis\": \"覆盖全部端点：见附件 api.md\", "
                                + "\"coverage\": [{\"criterionRef\": \"覆盖全部端点\", "
                                + "\"status\": \"covered\", \"method\": \"attachment\"}]}",
                        "stop", "llm", 100));

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
        verify(recordReviewService, never()).recordAutoReview(anyLong(), any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("§6.142 抽检发现放水（原 APPROVED 复审 REJECTED）→ discrepancy=true + timeline 观测")
    void shouldRecheckRecordWhenDiscrepancy() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
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
    @DisplayName("§6.142 抽检跳过：记录不存在 / INCONCLUSIVE 无原判 / 无可用 reviewer 均不调 LLM")
    void shouldSkipRecheckWhenNotEligible() {
        // 记录不存在
        when(recordReviewService.getById(RECORD_ID)).thenReturn(null);
        executor.recheckReviewRecord(RECORD_ID);
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));

        // INCONCLUSIVE：没有可复判的「原判」（RV-6 不入样）
        ReviewRecord inconclusive = approvedRecord(RECORD_ID);
        inconclusive.setResult(ReviewResult.INCONCLUSIVE);
        when(recordReviewService.getById(RECORD_ID)).thenReturn(inconclusive);
        executor.recheckReviewRecord(RECORD_ID);
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));

        // 无可用 reviewer → 跳过
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(reviewerPicker.pickSingle(any(), any())).thenReturn(null);
        executor.recheckReviewRecord(RECORD_ID);
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
    }

    @Test
    @DisplayName("RV-6 驳回侧：原 REJECTED 复判 APPROVED ⇒ discrepancy=true + direction=overturned_rejection（误伤）")
    void rv6_rejectedToApprovedRecordedAsOverturnedRejection() {
        ReviewRecord record = rejectedRecord(RECORD_ID);
        record.setSnapshot(ReviewSnapshotBuilder.build(
                List.of(), "接口清单已整理完毕，覆盖全部端点。"));
        when(recordReviewService.getById(RECORD_ID)).thenReturn(record);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\", "
                                + "\"analysis\": \"覆盖全部端点：见产出正文\", "
                                + "\"coverage\": [{\"criterionRef\": \"覆盖全部端点\", "
                                + "\"status\": \"covered\", \"method\": \"attachment\"}]}",
                        "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(recordReviewService).recordRecheck(
                eq(RECORD_ID), eq(SUB_TASK_ID), eq(ReviewResult.REJECTED), eq(ReviewResult.APPROVED),
                eq(true), eq(9L), any(), any(), any());
        verify(conversationService).addMessage(
                eq(SUB_TASK_ID), eq(9L), eq("assistant"), eq("agent"),
                argThat((String text) -> text.contains("误伤")), eq("subtask_recheck_result"));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_recheck_discrepancy"),
                eq(AgentRole.REVIEWER), eq(9L),
                argThat((Map<String, Object> payload) ->
                        "REJECTED".equals(payload.get("originalResult"))
                                && "APPROVED".equals(payload.get("recheckResult"))
                                && "overturned_rejection".equals(payload.get("direction"))));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
    }

    @Test
    @DisplayName("RV-6 驳回侧：原 REJECTED 复判 REJECTED ⇒ 一致（discrepancy=false）+ direction=consistent")
    void rv6_rejectedStaysRejected() {
        ReviewRecord record = rejectedRecord(RECORD_ID);
        record.setSnapshot(ReviewSnapshotBuilder.build(
                List.of(), "接口清单已整理完毕，覆盖全部端点。"));
        when(recordReviewService.getById(RECORD_ID)).thenReturn(record);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 1, \"issues\": \"[defect] 产出为编造\", \"comment\": \"\"}",
                        "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(recordReviewService).recordRecheck(
                eq(RECORD_ID), eq(SUB_TASK_ID), eq(ReviewResult.REJECTED), eq(ReviewResult.REJECTED),
                eq(false), eq(9L), any(), any(), any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_recheck_consistent"),
                eq(AgentRole.REVIEWER), eq(9L),
                argThat((Map<String, Object> payload) -> "consistent".equals(payload.get("direction"))));
    }

    @Test
    @DisplayName("RV-6 驳回侧：快照含冻结证据 ⇒ 当前视图已漂移仍用原判对象复判")
    void rv6_rejectedUsesFrozenEvidenceDespiteCurrentDrift() {
        ReviewRecord record = rejectedRecord(RECORD_ID);
        Map<String, Object> frozen = new HashMap<>();
        frozen.put("executionOutput", "冻结产出-ORIGINAL");
        frozen.put("attachmentList", "- original.md（MD, 100 bytes, 平台可直读）");
        frozen.put("attachmentContent", "### original.md\nORIGINAL-CONTENT");
        frozen.put("verificationSignal", "该提交携带验证证据（FROZEN）");
        frozen.put("selfReviewSignal", "（本轮自审已通过：FROZEN）");
        record.setSnapshot(ReviewSnapshotBuilder.build(List.of(), "原始产出", frozen));
        when(recordReviewService.getById(RECORD_ID)).thenReturn(record);
        // 当前视图已返工：产出不同（无冻结时会判 snapshot_drift）；冻结路径不读当前附件
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\", "
                                + "\"analysis\": \"覆盖全部端点：见原判附件\", "
                                + "\"coverage\": [{\"criterionRef\": \"覆盖全部端点\", "
                                + "\"status\": \"covered\", \"method\": \"attachment\"}]}",
                        "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        assertThat(captor.getValue().getUserPrompt())
                .contains("冻结产出-ORIGINAL")
                .contains("ORIGINAL-CONTENT")
                .contains("自审已通过：FROZEN")
                .doesNotContain("接口清单已整理完毕，覆盖全部端点。");
        verify(recordReviewService).recordRecheck(
                eq(RECORD_ID), eq(SUB_TASK_ID), eq(ReviewResult.REJECTED), eq(ReviewResult.APPROVED),
                eq(true), eq(9L), any(), any(), any());
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq(TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code()),
                eq(AgentRole.REVIEWER), isNull(), anyMap());
    }

    @Test
    @DisplayName("RV-6 驳回侧：无判定快照 ⇒ 跳过 reason=snapshot_missing（不调 LLM、不落日志）")
    void rv6_rejectedWithoutSnapshotSkipped() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(rejectedRecord(RECORD_ID));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());

        executor.recheckReviewRecord(RECORD_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(recordReviewService, never()).recordRecheck(anyLong(), anyLong(), any(), any(),
                anyBoolean(), anyLong(), any(), any(), any());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID),
                eq(TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code()), eq(AgentRole.REVIEWER), isNull(),
                argThat((Map<String, Object> payload) -> "snapshot_missing".equals(payload.get("reason"))));
    }

    @Test
    @DisplayName("§RV-4 抽检独立性：选人排除原判定人（review_record.reviewer_agent_id）")
    @SuppressWarnings("unchecked")
    void shouldExcludeOriginalReviewerWhenPicking() {
        ReviewRecord record = approvedRecord(RECORD_ID);
        when(recordReviewService.getById(RECORD_ID)).thenReturn(record);
        when(recordReviewService.getBySubTaskId(SUB_TASK_ID)).thenReturn(List.of(record));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        ArgumentCaptor<Set<Long>> captor = ArgumentCaptor.forClass(Set.class);
        verify(reviewerPicker).pickSingle(any(), captor.capture());
        assertThat(captor.getValue()).contains(record.getReviewerAgentId());
    }

    @Test
    @DisplayName("§RV-4 无可选独立 Reviewer → 跳过 + 抽检跳过事件（不抛异常、不调 LLM）")
    void shouldRecordSkipEventWhenNoIndependentReviewer() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(recordReviewService.getBySubTaskId(SUB_TASK_ID)).thenReturn(List.of(approvedRecord(RECORD_ID)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(reviewerPicker.pickSingle(any(), any())).thenReturn(null);

        executor.recheckReviewRecord(RECORD_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(taskTimelineService).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID),
                eq(TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code()), eq(AgentRole.REVIEWER), isNull(), anyMap());
    }

    @Test
    @DisplayName("§RV-2 复审命中 BLOCK（驳回却满分）→ 跳过事件 reason=inconclusive，不落 recheck log")
    void shouldRecordSkipEventWhenVerdictInconclusive() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 5, \"issues\": \"[defect] 缺证据\", \"comment\": \"\"}",
                        "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(recordReviewService, never()).recordRecheck(anyLong(), anyLong(), any(), any(),
                anyBoolean(), anyLong(), any(), any(), any());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID),
                eq(TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code()), eq(AgentRole.REVIEWER), isNull(),
                argThat((Map<String, Object> payload) -> "inconclusive".equals(payload.get("reason"))));
    }

    @Test
    @DisplayName("§RV-4 复审不可判定 → 抽检跳过事件 reason=unjudgeable（不再静默）")
    void shouldRecordSkipEventWhenVerdictUnjudgeable() {
        when(recordReviewService.getById(RECORD_ID)).thenReturn(approvedRecord(RECORD_ID));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("这不是 JSON 判定", "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(taskTimelineService).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID),
                eq(TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code()), eq(AgentRole.REVIEWER), isNull(),
                argThat((Map<String, Object> payload) -> "unjudgeable".equals(payload.get("reason"))));
    }

    @Test
    @DisplayName("RV-5 当前证据与原判定快照不一致 ⇒ 跳过（reason=snapshot_drift）且不调复判 LLM")
    void rv5_skipsWhenSnapshotDrift() {
        ReviewRecord record = approvedRecord(RECORD_ID);
        // 原判定时有一份附件；当前附件已被返工替换（mock 返回空）
        record.setSnapshot(ReviewSnapshotBuilder.build(
                List.of(new AttachmentView(1L, "api.md", "MD", "text/markdown", 100L, true)),
                "接口清单已整理完毕，覆盖全部端点。"));
        when(recordReviewService.getById(RECORD_ID)).thenReturn(record);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of());

        executor.recheckReviewRecord(RECORD_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(recordReviewService, never()).recordRecheck(anyLong(), anyLong(), any(), any(),
                anyBoolean(), anyLong(), any(), any(), any());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), eq(SUB_TASK_ID),
                eq(TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code()), eq(AgentRole.REVIEWER), isNull(),
                argThat((Map<String, Object> payload) -> "snapshot_drift".equals(payload.get("reason"))
                        && "attachments_changed".equals(payload.get("drift"))));
    }

    @Test
    @DisplayName("RV-5 快照与当前证据一致 ⇒ 照常复判（快照存在不改变正常抽检）")
    void rv5_proceedsWhenSnapshotMatches() {
        ReviewRecord record = approvedRecord(RECORD_ID);
        record.setSnapshot(ReviewSnapshotBuilder.build(List.of(), "接口清单已整理完毕，覆盖全部端点。"));
        when(recordReviewService.getById(RECORD_ID)).thenReturn(record);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\", "
                                + "\"analysis\": \"覆盖全部端点：见产出正文\", "
                                + "\"coverage\": [{\"criterionRef\": \"覆盖全部端点\", "
                                + "\"status\": \"covered\", \"method\": \"attachment\"}]}",
                        "stop", "llm", 100));

        executor.recheckReviewRecord(RECORD_ID);

        verify(recordReviewService).recordRecheck(
                eq(RECORD_ID), eq(SUB_TASK_ID), eq(ReviewResult.APPROVED), eq(ReviewResult.APPROVED),
                anyBoolean(), anyLong(), any(), any(), any());
    }
}
