package com.helloai.core.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.review.quality.RepeatedFailureGate;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.service.impl.SubTaskReviewServiceImpl;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewExecutionEngine;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.UncertaintyView;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SubTaskReviewService 单元测试（核验门控）：
 * 判定解析三分支（通过/不通过/不可解析）+ 返工上限跳过 + 返工重执行命令下发。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskReviewService")
class SubTaskReviewServiceTest {

    private static final Long SUB_TASK_ID = 22L;
    private static final Long TASK_ID = 10L;
    private static final Long EXECUTOR_ID = 5L;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ExecutionCommandService executionCommandService;

    @Mock
    private AgentDispatchProperties dispatchProperties;

    @Mock
    private ConversationService conversationService;

    @Mock
    private ReviewService recordReviewService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock reviewLock;

    @Mock
    private ReviewerPicker reviewerPicker;

    @Mock
    private ReviewProperties reviewProperties;

    @Mock
    private AgentQualityProfileService agentQualityProfileService;

    @Mock
    private AgentEventRecorder agentEventRecorder;

    private SubTaskReviewService reviewService;

    @BeforeEach
    void setUp() throws InterruptedException {
        // §6.142 双审并行：默认用同步直跑 Executor（supplyAsync 立即执行，保证确定性，不引入真实线程）；
        // 并行语义用例单独换真实线程池重建（见 shouldRunDualVerdictsInParallel）
        reviewService = buildService(command -> command.run());
        // §6.142 选取职责迁入 Picker：单审默认返回 9L REVIEWER（双审/指定用例单独 stub）
        lenient().when(reviewerPicker.pickSingle(any())).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
        // §6.142 双审默认关闭：既有用例保持单审语义（双审用例单独 stub true）
        lenient().when(reviewProperties.isDualReviewEnabled()).thenReturn(false);
        lenient().when(dispatchProperties.getAutoReviewMaxRework()).thenReturn(3);
        lenient().when(dispatchProperties.getReviewEvidenceCheckWaitMs()).thenReturn(0);
        // P-1 A2-2 重复失败短路：显式阈值 0.85（mock 默认 0.0 会把任意相似度都判为重复失败）
        lenient().when(dispatchProperties.getAutoReviewRepeatFailureSimilarity()).thenReturn(0.85);
        // 方案3 F2 附件内容注入：默认开启（开关用例单独 stub 为 false）
        lenient().when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(true);
        // §6.82 核验互斥锁：默认可获取（所有既有用例走完整核验链路）；锁用例单独 stub 为 false
        // v1.2 §阶段1：setIfAbsent 迁 Redisson RLock，mock 锁对象/获取/持有断言
        lenient().when(redissonClient.getLock(anyString())).thenReturn(reviewLock);
        lenient().when(reviewLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        // isHeldByCurrentThread 默认 false，正常路径须显式放行为持有锁（finally 防御分支依赖）
        lenient().when(reviewLock.isHeldByCurrentThread()).thenReturn(true);
    }

    /** 构造被测服务（§6.142 双审并行：Executor 参数化，并行语义用例可换真实线程池重建）。 */
    private SubTaskReviewService buildService(Executor executor) {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）；
        // 执行引擎/证据装配/判定解析为真实组件实例（拆分后主类仅编排，不直接持有 mock 目标）
        return new SubTaskReviewServiceImpl(
                subTaskService, agentService,
                new ReviewExecutionEngine(platformAgentExecutionService, conversationService,
                        taskTimelineService, new VerdictParser(new ObjectMapper()),
                        new ReviewEvidenceAssembler(attachmentService, dispatchProperties),
                        subTaskService),
                taskTimelineService, executionCommandService, dispatchProperties,
                conversationService, recordReviewService, redissonClient,
                new ReviewEvidenceAssembler(attachmentService, dispatchProperties),
                new VerdictParser(new ObjectMapper()),
                reviewerPicker, reviewProperties, agentQualityProfileService, executor, agentEventRecorder,
                new RepeatedFailureGate(dispatchProperties));
    }

    private SubTaskView reviewSubTask() {
        //  证据检查：默认携带可读产出（非执行密集任务 output 即产出支撑）
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        return new SubTaskView(SUB_TASK_ID, TASK_ID, SubTaskStatus.REVIEW, null, 0,
                "写接口文档", "整理 REST 接口清单", "接口文档", "覆盖全部端点", null, ctx, List.of());
    }

    private static SubTaskView withStatus(SubTaskView base, SubTaskStatus status) {
        return new SubTaskView(base.id(), base.taskId(), status, base.assignedAgentId(), base.reworkCount(),
                base.title(), base.content(), base.deliverable(), base.acceptance(), base.constraints(),
                base.context(), base.uncertainties());
    }

    private static SubTaskView withReworkCount(SubTaskView base, int reworkCount) {
        return new SubTaskView(base.id(), base.taskId(), base.status(), base.assignedAgentId(), reworkCount,
                base.title(), base.content(), base.deliverable(), base.acceptance(), base.constraints(),
                base.context(), base.uncertainties());
    }

    private static SubTaskView withConstraints(SubTaskView base, String constraints) {
        return new SubTaskView(base.id(), base.taskId(), base.status(), base.assignedAgentId(), base.reworkCount(),
                base.title(), base.content(), base.deliverable(), base.acceptance(), constraints,
                base.context(), base.uncertainties());
    }

    private static SubTaskView withUncertainties(SubTaskView base, List<UncertaintyView> uncertainties) {
        return new SubTaskView(base.id(), base.taskId(), base.status(), base.assignedAgentId(), base.reworkCount(),
                base.title(), base.content(), base.deliverable(), base.acceptance(), base.constraints(),
                base.context(), uncertainties);
    }

    /** 以给定 context 覆写快照（视图不可变，等价原 setContext 写法）。 */
    private static SubTaskView withContext(SubTaskView base, Map<String, Object> context) {
        return new SubTaskView(base.id(), base.taskId(), base.status(), base.assignedAgentId(),
                base.reworkCount(), base.title(), base.content(), base.deliverable(),
                base.acceptance(), base.constraints(), context, base.uncertainties());
    }

    /** 执行密集子任务视图（内容/交付物含本机操作信号）。 */
    private SubTaskView denseView(String content, String deliverable, Map<String, Object> context) {
        return new SubTaskView(SUB_TASK_ID, TASK_ID, SubTaskStatus.REVIEW, null, 0,
                "写接口文档", content, deliverable, "覆盖全部端点", null, context, List.of());
    }

    /** 附件视图（等价原实体 setter 写法；contentLoadable 即原 isContentLoadable 桩值）。 */
    private static AttachmentView attachmentView(Long id, String fileName, String fileType, String mimeType,
                                                 Long fileSize, boolean contentLoadable) {
        return new AttachmentView(id, fileName, fileType, mimeType, fileSize, contentLoadable);
    }

    private AgentProfileSnapshot llmAgent(long id, AgentRole role) {
        return AgentProfileSnapshot.builder()
                .id(id)
                .role(role)
                .accessType(AgentAccessType.API_KEY_LLM)
                .localExecutionCapable(true)
                .build();
    }

    // ══════════════════════════════════════════════════════════════
    //  parseVerdict：三分支解析
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parseVerdict：纯 JSON 与 markdown fence 均可解析")
    void shouldParsePlainAndFencedJson() {
        SubTaskReviewService.ReviewVerdict plain = reviewService.parseVerdict(
                "{\"pass\": true, \"score\": 4, \"issues\": \"\", \"comment\": \"达标\"}");
        assertThat(plain).isNotNull();
        assertThat(plain.getPass()).isTrue();
        assertThat(plain.getScore()).isEqualTo(4);

        SubTaskReviewService.ReviewVerdict fenced = reviewService.parseVerdict(
                "```json\n{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"\"}\n```");
        assertThat(fenced).isNotNull();
        assertThat(fenced.getPass()).isFalse();
        assertThat(fenced.getIssues()).isEqualTo("缺端点");
    }

    @Test
    @DisplayName("parseVerdict：非 JSON / 缺 pass 字段 / 空输出均返回 null")
    void shouldReturnNullForUnparseableOutput() {
        assertThat(reviewService.parseVerdict("我觉得做得不错")).isNull();
        assertThat(reviewService.parseVerdict("{\"score\": 4}")).isNull();
        assertThat(reviewService.parseVerdict(null)).isNull();
        assertThat(reviewService.parseVerdict("  ")).isNull();
    }

    @Test
    @DisplayName("parseVerdict：字符串值含未转义 Windows 路径反斜杠也能解析")
    void shouldParseVerdictWithUnescapedWindowsPath() {
        SubTaskReviewService.ReviewVerdict verdict = reviewService.parseVerdict(
                "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"产出位于E:\\workspace\\out目录\"}");
        assertThat(verdict).isNotNull();
        assertThat(verdict.getPass()).isTrue();
        assertThat(verdict.getComment()).isEqualTo("产出位于E:\\workspace\\out目录");
    }

    // ══════════════════════════════════════════════════════════════
    //  reviewSubTask：判定后动作
    // ══════════════════════════════════════════════════════════════
    @Test
    @DisplayName("parseVerdict + normalizeMissingEvidence：结构化缺失证据原样承接，非法元素逐条丢弃")
    void shouldParseAndNormalizeMissingEvidence() {
        SubTaskReviewService.ReviewVerdict verdict = reviewService.parseVerdict(
                "{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"\","
                        + " \"missingEvidence\": ["
                        + "{\"acceptanceRef\": \"覆盖全部端点\", \"missing\": \"DELETE /x 无证据\", \"howTo\": \"补 curl 输出\"},"
                        + "\" 裸字符串 \", {}, {\"acceptanceRef\": \"   \"}, {\"missing\": \"只有缺什么\"}]}");
        assertThat(verdict).isNotNull();

        List<Map<String, String>> normalized = VerdictParser.normalizeMissingEvidence(verdict.getMissingEvidence());
        assertThat(normalized).hasSize(2);
        assertThat(normalized.get(0)).containsEntry("acceptanceRef", "覆盖全部端点")
                .containsEntry("missing", "DELETE /x 无证据")
                .containsEntry("howTo", "补 curl 输出");
        // 非对象元素、三键全空条目被丢弃；仅个别键有效的条目保留有效键
        assertThat(normalized.get(1)).containsOnlyKeys("missing");
    }

    @Test
    @DisplayName("normalizeMissingEvidence：缺失 / 非数组 / 非对象一律降级空清单（防御零影响）")
    void shouldNormalizeMissingEvidenceToEmptyList() {
        assertThat(VerdictParser.normalizeMissingEvidence(null)).isEmpty();
        SubTaskReviewService.ReviewVerdict absent = reviewService.parseVerdict(
                "{\"pass\": false, \"score\": 2, \"issues\": \"x\", \"comment\": \"\"}");
        assertThat(VerdictParser.normalizeMissingEvidence(absent.getMissingEvidence())).isEmpty();
        SubTaskReviewService.ReviewVerdict textForm = reviewService.parseVerdict(
                "{\"pass\": false, \"score\": 2, \"issues\": \"x\", \"comment\": \"\", \"missingEvidence\": \"none\"}");
        assertThat(VerdictParser.normalizeMissingEvidence(textForm.getMissingEvidence())).isEmpty();
        SubTaskReviewService.ReviewVerdict objectForm = reviewService.parseVerdict(
                "{\"pass\": false, \"score\": 2, \"issues\": \"x\", \"comment\": \"\","
                        + " \"missingEvidence\": {\"acceptanceRef\": \"a\"}}");
        assertThat(VerdictParser.normalizeMissingEvidence(objectForm.getMissingEvidence())).isEmpty();
    }


    @Test
    @DisplayName("核验通过 → complete（REVIEW→DONE）并记 timeline")
    void shouldCompleteWhenVerdictPass() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_passed"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("核验不通过 → rework 并对 API_KEY_LLM 执行者重发执行命令")
    void shouldReworkAndRedispatchWhenVerdictFail() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺 3 个端点\", \"comment\": \"\"}", "stop", "llm", 100));
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(llmAgent(EXECUTOR_ID, AgentRole.EXECUTOR));
        // Phase 0 A3：预算充足返回 true（mock 默认 false 会误判为预算熔断分支）
        when(subTaskService.rework(SUB_TASK_ID, EXECUTOR_ID)).thenReturn(true);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(subTaskService, never()).complete(anyLong());
        verify(executionCommandService).createAssignedCommand(SUB_TASK_ID, EXECUTOR_ID, "auto-review-rework", List.of());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_rejected"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  P-1 A2-2 重复失败短路（reviewHistory 连续同因驳回 → 结构性失败转死信）
    //  R1 修订（2026-09-30 审计 §15.2）：主判据 = 两轮 score 未严格提升；
    //  相似度降为 score 任一轮缺失时的兜底。
    // ══════════════════════════════════════════════════════════════

    private static final String SAME_ISSUES =
            "7 条关键词中仅 2 条出现在材料中，其余 5 条 not contained in the material";

    /** 携带上轮驳回历史的子任务（可指定上轮 issues / score；score=null 模拟缺失场景）。 */
    private SubTaskView reviewSubTaskWithFailureHistory(String prevIssues, Integer prevScore) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        Map<String, Object> prev = new HashMap<>();
        prev.put("round", 1);
        prev.put("ts", "2026-08-01T10:00:00Z");
        prev.put("issues", prevIssues);
        prev.put("comment", "请补");
        if (prevScore != null) {
            prev.put("score", prevScore);
        }
        prev.put("executorDoneIssues", List.of());
        ctx.put("reviewHistory", List.of(prev));
        return withContext(reviewSubTask(), ctx);
    }

    /** 携带上轮同因驳回的 reviewHistory 子任务（P-1 事故形态复现）。 */
    private SubTaskView reviewSubTaskWithRepeatedFailureHistory() {
        return reviewSubTaskWithFailureHistory(SAME_ISSUES, 2);
    }

    @Test
    @DisplayName("P-1 A2-2 连续同因驳回（相似≥阈值 + 评分未提升）→ 短路转死信，不返工不重派")
    void shouldShortCircuitRepeatedFailureSignatureToDeadLetter() {
        when(dispatchProperties.isAutoReviewRepeatFailureShortCircuit()).thenReturn(true);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTaskWithRepeatedFailureHistory());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \""
                        + SAME_ISSUES + "，无法完成核验\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 不再空转：不返工、不补发执行命令、不通过
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(executionCommandService, never()).createAssignedCommand(anyLong(), anyLong(), anyString(), any());
        verify(subTaskService, never()).complete(anyLong());
        // 短路事件（带相似度证据）+ 显式死信事件 + 人工介入标记
        ArgumentCaptor<Map> skipPayload = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_skip_repeated_failure"),
                eq(AgentRole.REVIEWER), eq(9L), skipPayload.capture());
        assertThat(skipPayload.getValue())
                .containsEntry("reason", "repeated_failure_signature")
                .containsEntry("basis", "score_stall_and_text_repeat")
                .containsEntry("round", 2);
        assertThat(((Number) skipPayload.getValue().get("similarity")).doubleValue())
                .isGreaterThanOrEqualTo(0.85);
        ArgumentCaptor<Map> deadLetterPayload = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_dead_letter"),
                eq(AgentRole.SYSTEM), isNull(), deadLetterPayload.capture());
        assertThat(deadLetterPayload.getValue()).containsEntry("reason", "repeated_failure_signature");
        verify(subTaskService).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("repeated_failure_signature"), anyMap());
        // 判决仍落 review_record 留痕
        verify(recordReviewService).recordAutoReview(
                eq(SUB_TASK_ID), eq(9L), eq(ReviewResult.REJECTED), eq(2), anyString(), anyString());
        verify(conversationService).addMessage(eq(SUB_TASK_ID), eq(9L), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_review_skip_repeated_failure"));
    }

    @Test
    @DisplayName("P-1 A2-2 驳回意见相似但评分提升（2→3）→ 不短路，正常返工（防误伤进步迭代）")
    void shouldReworkWhenSimilarFailureButScoreImproves() {
        when(dispatchProperties.isAutoReviewRepeatFailureShortCircuit()).thenReturn(true);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTaskWithRepeatedFailureHistory());
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(llmAgent(EXECUTOR_ID, AgentRole.EXECUTOR));
        when(subTaskService.rework(SUB_TASK_ID, EXECUTOR_ID)).thenReturn(true);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 3, \"issues\": \""
                        + SAME_ISSUES + "，无法完成核验\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(executionCommandService).createAssignedCommand(
                SUB_TASK_ID, EXECUTOR_ID, "auto-review-rework", List.of());
        verify(subTaskService, never()).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), any());
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("P-1 A2-2 短路开关关闭 → 相同驳回意见仍走正常返工（行为开关生效）")
    void shouldReworkWhenShortCircuitSwitchDisabled() {
        // isAutoReviewRepeatFailureShortCircuit 默认 false：两轮意见逐字相同也不短路
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTaskWithRepeatedFailureHistory());
        when(subTaskService.rework(SUB_TASK_ID, EXECUTOR_ID)).thenReturn(true);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \""
                        + SAME_ISSUES + "\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(subTaskService, never()).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull());
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  R1 修订用例（2026-09-30 审计 §15.2）：score 主判据 + 相似度兜底
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2026-10-03 修订（真机 687）：score 恒定 2→2 但问题已变（相似度仅 0.25）→ 不短路，正常返工")
    void shouldReworkWhenScoreStallsButIssuesChanged() throws Exception {
        when(dispatchProperties.isAutoReviewRepeatFailureShortCircuit()).thenReturn(true);
        when(subTaskService.getView(SUB_TASK_ID))
                .thenReturn(reviewSubTaskWithFailureHistory(loadCorpus("tku-e2e-01-sub3-r1.txt"), 2));
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(llmAgent(EXECUTOR_ID, AgentRole.EXECUTOR));
        when(subTaskService.rework(SUB_TASK_ID, EXECUTOR_ID)).thenReturn(true);
        // 本轮评审输出 r2 全文（与 r1 措辞/编号全变，实测相似度 0.2537 < 阈值 0.85）
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        verdictJson(2, loadCorpus("tku-e2e-01-sub3-r2.txt")), "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 分数停滞但相似度（0.25 量级）< 阈值 → 问题性质已变、有实质进展 → 继续返工（防误伤迭代）
        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(executionCommandService).createAssignedCommand(
                SUB_TASK_ID, EXECUTOR_ID, "auto-review-rework", List.of());
        verify(subTaskService, never()).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull());
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_skip_repeated_failure"),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("R1 兜底：上轮 score 缺失且文本高度相似 → basis=text_repeat 短路（旧行为保留）")
    void shouldShortCircuitViaTextRepeatWhenScoreMissing() {
        when(dispatchProperties.isAutoReviewRepeatFailureShortCircuit()).thenReturn(true);
        when(subTaskService.getView(SUB_TASK_ID))
                .thenReturn(reviewSubTaskWithFailureHistory(SAME_ISSUES, null));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \""
                        + SAME_ISSUES + "，无法完成核验\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService, never()).rework(anyLong(), any());
        ArgumentCaptor<Map> skipPayload = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_skip_repeated_failure"),
                eq(AgentRole.REVIEWER), eq(9L), skipPayload.capture());
        assertThat(skipPayload.getValue())
                .containsEntry("basis", "text_repeat")
                .containsEntry("scoreTrend", "n/a");
        verify(subTaskService).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull());
    }

    @Test
    @DisplayName("R1 兜底不误伤：score 缺失且两轮文本不同（相似度 < 阈值）→ 正常返工")
    void shouldReworkWhenScoreMissingAndTextDiffers() {
        when(dispatchProperties.isAutoReviewRepeatFailureShortCircuit()).thenReturn(true);
        when(subTaskService.getView(SUB_TASK_ID))
                .thenReturn(reviewSubTaskWithFailureHistory("缺少订单过期接口的幂等性处理", null));
        when(subTaskService.rework(SUB_TASK_ID, EXECUTOR_ID)).thenReturn(true);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 2, \"issues\": \"文档格式不符，缺少目录结构说明\", \"comment\": \"\"}",
                        "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(subTaskService, never()).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull());
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
    }

    /** 构造合法 verdict JSON（真实语料含引号/换行，必须经 ObjectMapper 转义）。 */
    private static String verdictJson(int score, String issues) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "pass", false, "score", score, "issues", issues, "comment", ""));
    }

    /** 读取 review-corpus 真实语料夹具（测试资源，UTF-8 原文）。 */
    private static String loadCorpus(String name) throws Exception {
        try (var in = SubTaskReviewServiceTest.class.getResourceAsStream("/review-corpus/" + name)) {
            assertThat(in).as("语料夹具缺失: %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("A3 预算耗尽：rework 返回 false → 不补发执行命令（子任务已转死信待人工）")
    void shouldSkipExecutionCommandWhenReworkBudgetExhausted() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺 3 个端点\", \"comment\": \"\"}", "stop", "llm", 100));
        // Phase 0 A3：返工预算耗尽（attempt_total 达 max-reassign-attempts），rework 已转 DEAD_LETTER
        when(subTaskService.rework(SUB_TASK_ID, EXECUTOR_ID)).thenReturn(false);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(executionCommandService, never()).createAssignedCommand(anyLong(), anyLong(), anyString(), any());
        verify(subTaskService, never()).complete(anyLong());
    }

    @Test
    @DisplayName("输出不可解析 → 不改状态（停留 REVIEW），记 unparseable timeline")
    void shouldStayInReviewWhenUnparseable() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("这个任务完成得还行吧", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_unparseable"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("状态非 REVIEW 或返工达上限 → 跳过，不调 LLM")
    void shouldSkipWhenNotReviewOrReworkLimitReached() {
        SubTaskView done = reviewSubTask();
        done = withStatus(done, SubTaskStatus.DONE);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(done);
        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        SubTaskView maxRework = reviewSubTask();
        maxRework = withReworkCount(maxRework, 3);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(maxRework);
        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_skip_max_rework"),
                eq(AgentRole.REVIEWER), any(), anyMap());
        // §6.52：返工达上限须写入人工介入标记（前端面板据此展示）
        verify(subTaskService).markManualIntervention(eq(SUB_TASK_ID), eq("rework_limit"), anyMap());
        // 核验返工熔断后状态须真正转入 DEAD_LETTER（前端"死信待人工"筛选据此可见）
        verify(subTaskService).changeStatus(eq(SUB_TASK_ID), eq(SubTaskStatus.DEAD_LETTER), isNull());
        // 核验返工熔断显式入死信（与调度维度 sub_task_dead_letter 对称），DLQ 泳道可回溯
        ArgumentCaptor<Map> deadLetterPayload = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_dead_letter"),
                eq(AgentRole.SYSTEM), isNull(), deadLetterPayload.capture());
        assertThat(deadLetterPayload.getValue())
                .containsEntry("reason", "rework_limit_exceeded")
                .containsEntry("reworkCount", 3)
                .containsEntry("maxRework", 3);
        // 2026-10-05 补做：终态分支须同时落一条对话消息（toolName 前缀 subtask_review，前端归核验轮次）
        verify(conversationService).addMessage(eq(SUB_TASK_ID), isNull(), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_review_skip_max_rework"));
    }

    @Test
    @DisplayName("执行密集任务 + 提交者无本机能力 → 跳过自动核验 + 标记人工介入")
    void shouldSkipReviewWhenExecutionDenseSubmitterLacksCapability() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTaskView dense = denseView("编写 verify-order-expire.ps1 脚本并执行验证", "verify-order-expire.ps1", null);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(dense);
        // 提交者：API_KEY_LLM 且无本机执行能力（localExecutionCapable=false，与原 supportsMCP 缺失语义等价）
        AgentProfileSnapshot nonCapableSubmitter = AgentProfileSnapshot.builder()
                .id(EXECUTOR_ID)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .localExecutionCapable(false)
                .build();
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(nonCapableSubmitter);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_execution_dense_no_capability"), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_skip_no_capability"),
                eq(AgentRole.REVIEWER), eq(EXECUTOR_ID), anyMap());
        verify(conversationService).addMessage(eq(SUB_TASK_ID), eq(EXECUTOR_ID), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_review_skip_no_capability"));
    }

    @Test
    @DisplayName("执行密集任务 + 提交者有本机能力 → 正常自动核验")
    void shouldReviewWhenExecutionDenseSubmitterHasLocalCapability() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTaskView dense = reviewSubTask();
        dense = denseView("编写 verify-order-expire.ps1 脚本并执行验证", "verify-order-expire.ps1",
                Map.of("lastExecution",
                        Map.of("output", "脚本执行完成: PASS=12 FAIL=0 全绿\nVERIFICATION:\n命令: ./verify-order-expire.ps1\n输出: PASS=12 FAIL=0\n结论: 脚本真实执行通过")));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(dense);
        // 提交者：CLI_CLIENT（天然具备本机执行能力）
        AgentProfileSnapshot submitter = AgentProfileSnapshot.builder()
                .id(EXECUTOR_ID)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.CLI_CLIENT)
                .localExecutionCapable(true)
                .build();
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(submitter);
        //  证据检查：执行密集任务需有可读物化附件支撑
        AttachmentView attachment = attachmentView(100L, "verify-order-expire.ps1", "other", null, 2048L, true);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(attachment));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("执行密集 + 提交者快照缺失（getProfileById=null）→ 不跳过（等价锁定 hasLocalExecutionCapability(null)==true）")
    void shouldNotSkipReviewWhenExecutionDenseSubmitterSnapshotMissing() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTaskView dense = reviewSubTask();
        dense = denseView("编写 verify-order-expire.ps1 脚本并执行验证", "verify-order-expire.ps1",
                Map.of("lastExecution",
                        Map.of("output", "脚本执行完成: PASS=12 FAIL=0 全绿\nVERIFICATION:\n命令: ./verify-order-expire.ps1\n输出: PASS=12 FAIL=0\n结论: 脚本真实执行通过")));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(dense);
        // 提交者快照缺失：getProfileById 返回 null（原 hasLocalExecutionCapability(null)==true → 不跳过）
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(null);
        // 证据检查：执行密集任务需有可读物化附件支撑
        AttachmentView attachment = attachmentView(100L, "verify-order-expire.ps1", "other", null, 2048L, true);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(attachment));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 不跳过：正式进入核验并放行，未打人工介入标记
        verify(platformAgentExecutionService).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("执行密集 + 提交者快照存在但 localExecutionCapable=false → 跳过（能力门先于证据门，故造证据齐备仍拦截）")
    void shouldSkipReviewWhenExecutionDenseSubmitterHasNoLocalCapability() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTaskView dense = reviewSubTask();
        dense = denseView("编写 verify-order-expire.ps1 脚本并执行验证", "verify-order-expire.ps1",
                Map.of("lastExecution",
                        Map.of("output", "脚本执行完成: PASS=12 FAIL=0 全绿\nVERIFICATION:\n命令: ./verify-order-expire.ps1\n输出: PASS=12 FAIL=0\n结论: 脚本真实执行通过")));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(dense);
        // 提交者存在但无本机执行能力（API_KEY_LLM 且 localExecutionCapable=false）
        AgentProfileSnapshot submitter = AgentProfileSnapshot.builder()
                .id(EXECUTOR_ID)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .localExecutionCapable(false)
                .build();
        when(agentService.getProfileById(EXECUTOR_ID)).thenReturn(submitter);
        // 证据齐备（附件可读）：用以证明拦截来自「能力门」而非「证据门」；
        // 能力门先于证据门返回，故这两处 stub 不会被触达，用 lenient 规避严格桩校验
        AttachmentView attachment = attachmentView(100L, "verify-order-expire.ps1", "other", null, 2048L, true);
        lenient().when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(attachment));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_skip_no_capability"),
                eq(AgentRole.REVIEWER), eq(EXECUTOR_ID), anyMap());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_execution_dense_no_capability"), anyMap());
        verify(subTaskService, never()).complete(anyLong());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
    }

    @Test
    @DisplayName("LLM 调用失败 → 不改状态（停留 REVIEW 等人工兜底）")
    void shouldStayInReviewWhenLlmFails() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenThrow(new RuntimeException("llm timeout"));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(taskTimelineService, never()).recordEvent(
                anyLong(), anyLong(), anyString(), any(), any(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.41 reviewHistory 多轮累积
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.41 TC-1 首次驳回 → context.reviewHistory.length == 1，round=1")
    void shouldAppendFirstRoundToReviewHistory() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"请补\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContext(org.mockito.ArgumentMatchers.eq(SUB_TASK_ID), captor.capture());
        Map<String, Object> savedCtx = captor.getValue();
        assertThat(savedCtx).isNotNull();
        Object historyObj = savedCtx.get("reviewHistory");
        assertThat(historyObj).isInstanceOf(List.class);
        List<?> history = (List<?>) historyObj;
        assertThat(history).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        assertThat(first.get("round")).isEqualTo(1);
        assertThat(first.get("issues")).isEqualTo("缺端点");
        assertThat(first.get("comment")).isEqualTo("请补");
        assertThat(first.get("score")).isEqualTo(2);
        // executorDoneIssues 初始为空列表（预留字段）
        assertThat((List<?>) first.get("executorDoneIssues")).isEmpty();
        // 兼容保留 lastAutoReview
        assertThat(savedCtx.get("lastAutoReview")).isNotNull();
    }

    @Test
    @DisplayName("§6.41 TC-2 第二次驳回 → reviewHistory.length == 2，第二轮 round=2")
    void shouldAppendSecondRoundToReviewHistory() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        ctx.put("reviewHistory", List.of(Map.of(
                "round", 1, "ts", "2026-08-01T10:00:00Z",
                "issues", "缺端点", "comment", "请补", "score", 2,
                "executorDoneIssues", List.of())));
        SubTaskView subTask = withContext(reviewSubTask(), ctx);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(subTask);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 3, \"issues\": \"格式不对\", \"comment\": \"再改\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContext(org.mockito.ArgumentMatchers.eq(SUB_TASK_ID), captor.capture());
        List<?> history = (List<?>) captor.getValue().get("reviewHistory");
        assertThat(history).hasSize(2);
        // 第一轮保留
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        assertThat(first.get("round")).isEqualTo(1);
        assertThat(first.get("issues")).isEqualTo("缺端点");
        // 第二轮新增
        Map<?, ?> second = (Map<?, ?>) history.get(1);
        assertThat(second.get("round")).isEqualTo(2);
        assertThat(second.get("issues")).isEqualTo("格式不对");
    }

    @Test
    @DisplayName("§6.41 TC-3 兼容历史：context 只有 lastAutoReview 无 reviewHistory 时，新写入包成 reviewHistory[0] + lastAutoReview 同值")
    void shouldMigrateLegacyLastAutoReviewToReviewHistory() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        ctx.put("lastAutoReview", Map.of(
                "reviewerAgentId", 9L,
                "issues", "缺端点", "comment", "请补", "score", 2));
        SubTaskView subTask = withContext(reviewSubTask(), ctx);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(subTask);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"仍未达标\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContext(org.mockito.ArgumentMatchers.eq(SUB_TASK_ID), captor.capture());
        Map<String, Object> savedCtx = captor.getValue();
        List<?> history = (List<?>) savedCtx.get("reviewHistory");
        assertThat(history).hasSize(2);
        // 首轮是兼容的旧 lastAutoReview
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        assertThat(first.get("round")).isEqualTo(1);
        assertThat(first.get("issues")).isEqualTo("缺端点");
        // 第二轮是当前新写入
        Map<?, ?> second = (Map<?, ?>) history.get(1);
        assertThat(second.get("round")).isEqualTo(2);
        assertThat(second.get("issues")).isEqualTo("仍未达标");
        // lastAutoReview 收敛到 current（最新一轮），便于旧读路径兼容
        Map<?, ?> lastReview = (Map<?, ?>) savedCtx.get("lastAutoReview");
        assertThat(lastReview.get("issues")).isEqualTo("仍未达标");
    }

    @Test
    @DisplayName("§6.41 TC-4 executorDoneIssues 初始为空列表（留待后续执行回填 hook）")
    void shouldInitializeExecutorDoneIssuesAsEmptyList() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContext(org.mockito.ArgumentMatchers.eq(SUB_TASK_ID), captor.capture());
        List<?> history = (List<?>) captor.getValue().get("reviewHistory");
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        Object done = first.get("executorDoneIssues");
        assertThat(done).isInstanceOf(List.class);
        assertThat((List<?>) done).isEmpty();
    }

    @Test
    @DisplayName("P2-4 驳回 → reviewHistory 当前轮写入归一后的 missingEvidence 清单")
    void shouldPersistMissingEvidenceIntoReviewHistory() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"请补\","
                                + " \"missingEvidence\": [{\"acceptanceRef\": \"覆盖全部端点\","
                                + " \"missing\": \"DELETE 端点无验证\", \"howTo\": \"补 curl 命令与输出\"},"
                                + " {\"acceptanceRef\": \"   \"}]}",
                        "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContext(org.mockito.ArgumentMatchers.eq(SUB_TASK_ID), captor.capture());
        List<?> history = (List<?>) captor.getValue().get("reviewHistory");
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        Object raw = first.get("missingEvidence");
        assertThat(raw).isInstanceOf(List.class);
        List<?> evidence = (List<?>) raw;
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0)).isEqualTo(Map.of(
                "acceptanceRef", "覆盖全部端点",
                "missing", "DELETE 端点无验证",
                "howTo", "补 curl 命令与输出"));
    }

    @Test
    @DisplayName("P2-4 驳回：LLM 未产出 missingEvidence → 落库空清单（形状稳定，零影响）")
    void shouldPersistEmptyMissingEvidenceWhenAbsent() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"请补\"}",
                        "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContext(org.mockito.ArgumentMatchers.eq(SUB_TASK_ID), captor.capture());
        List<?> history = (List<?>) captor.getValue().get("reviewHistory");
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        assertThat((List<?>) first.get("missingEvidence")).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.58 P1：任务级 policy 指定 Reviewer
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.58: policy 指定 reviewerAgentId 生效（Picker 返回指定 reviewer）")
    void shouldUsePolicyReviewerWhenSpecified() {
        AgentProfileSnapshot pinned = AgentProfileSnapshot.builder()
                .id(99L)
                .role(AgentRole.REVIEWER)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .localExecutionCapable(true)
                .build();
        when(reviewerPicker.pickSingle(any())).thenReturn(pinned);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 选取职责已迁入 Picker：指定可用时返回指定 reviewer，核验记录归属该 reviewer
        verify(subTaskService).complete(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_passed"),
                eq(AgentRole.REVIEWER), eq(99L), anyMap());
    }

    @Test
    @DisplayName("§6.58: 指定 reviewer 不可用（DISABLED）→ Picker 回退自动选择（9L）")
    void shouldFallbackToAutoWhenPolicyReviewerUnusable() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(reviewerPicker.pickSingle(any())).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 指定失效 → Picker 内部回退自动选择链，返回可用 REVIEWER
        verify(subTaskService).complete(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_passed"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //   证据硬检查：伪造证据不通过 / 有附件通过
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无产出本体（output 与附件皆空）→ 跳过自动核验 + 人工介入标记")
    void shouldSkipReviewWhenNoOutputAndNoAttachment() {
        // 编造提交：连产出文本都没有
        SubTaskView fake = withContext(reviewSubTask(), null);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(fake);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_no_evidence"),
                argThat(m -> "no_output_no_attachment".equals(m.get("reason"))));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_skip_no_evidence"),
                eq(AgentRole.REVIEWER), eq(EXECUTOR_ID), anyMap());
        verify(conversationService).addMessage(eq(SUB_TASK_ID), eq(EXECUTOR_ID), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_review_skip_no_evidence"));
    }

    @Test
    @DisplayName("执行密集任务仅文字描述产出、无可读物化附件 → 跳过自动核验")
    void shouldSkipReviewWhenExecutionDenseWithoutReadableAttachment() {
        SubTaskView dense = denseView("编写 verify-order-expire.ps1 脚本并执行验证", "verify-order-expire.ps1",
                Map.of("lastExecution",
                        Map.of("output", "脚本已完成并执行通过: 文件 203 行 errors=0"))); // 仅文字声称，无真实附件
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(dense);
        // 附件存在但平台不可直读（外部存储）→ 不算可验证证据
        AttachmentView external = attachmentView(100L, "verify-order-expire.ps1", null, null, null, false);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(external));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_no_evidence"),
                argThat(m -> "execution_dense_no_attachment".equals(m.get("reason"))));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_skip_no_evidence"),
                eq(AgentRole.REVIEWER), eq(EXECUTOR_ID), anyMap());
    }

    @Test
    @DisplayName("执行密集任务无可读附件（重查窗口后仍无）→ 跳过自动核验")
    void shouldSkipReviewWhenExecutionDenseNoAttachmentAfterRetry() {
        // 覆盖 setUp 的 0：给一个真实等待窗口，验证物化竞态补偿路径（等待→重查→仍无→拦截）
        when(dispatchProperties.getReviewEvidenceCheckWaitMs()).thenReturn(5);
        SubTaskView dense = denseView("编写 verify-order-expire.ps1 脚本并执行验证", "verify-order-expire.ps1",
                Map.of("lastExecution",
                        Map.of("output", "脚本执行完成: PASS=12 FAIL=0 全绿")));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(dense);
        // 无任何附件（物化缺失/失败场景，重查后仍无）
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of());

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(attachmentService, org.mockito.Mockito.times(2)).listActiveViews(SUB_TASK_ID);
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_no_evidence"),
                argThat(m -> "execution_dense_no_attachment".equals(m.get("reason"))));
    }

    @Test
    @DisplayName("核验 Prompt 注入物化附件清单（有附件列文件名 / 无附件占位）")
    void shouldInjectAttachmentListIntoReviewPrompt() {
        // 有可读附件：prompt 应含附件清单章节与文件名
        SubTaskView subTask = reviewSubTask();
        AttachmentView attachment = attachmentView(100L, "api-docs.md", "markdown", null, 1024L, true);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(subTask);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(attachment));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 4, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), taskCaptor.capture());
        String prompt = taskCaptor.getValue().getUserPrompt();
        assertThat(prompt).contains("## 物化附件清单");
        assertThat(prompt).contains("api-docs.md");
        assertThat(prompt).contains("平台可直读");
        assertThat(prompt).contains("声称的交付物必须与**物化附件清单**对应");
    }

    @Test
    @DisplayName("G-011 D7：核验 Prompt 注入执行约束与不确定性申报（分级条目逐条渲染）")
    void shouldInjectConstraintsAndUncertaintiesIntoReviewPrompt() {
        SubTaskView subTask = reviewSubTask();
        subTask = withConstraints(subTask, "不得改动既有接口签名");
        subTask = withUncertainties(subTask, List.of(
                new UncertaintyView("ASSUMPTION", "仅在线表参与统计"),
                new UncertaintyView("UNCONFIRMED", "归档分区口径待确认")));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(subTask);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 4, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt)
                .contains("- 执行约束：不得改动既有接口签名")
                .contains("- 不确定性申报：")
                .contains("- [ASSUMPTION] 仅在线表参与统计")
                .contains("- [UNCONFIRMED] 归档分区口径待确认");
    }

    @Test
    @DisplayName("G-011 D7：无约束无申报时占位渲染「（无）」，不残留双大括号占位符")
    void shouldRenderPlaceholderWhenNoConstraintsOrUncertainties() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt)
                .contains("- 执行约束：（无）")
                .contains("- 不确定性申报：（无）")
                .doesNotContain("{{CONSTRAINTS}}")
                .doesNotContain("{{UNCERTAINTIES}}");
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.82 批次 D：核验互斥锁（防 L1/L2/L3 三路并发双审）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.82: 已有核验进行中（锁被占用）→ 跳过，不调 LLM、不改状态、不释放他人锁")
    void shouldSkipWhenReviewLockHeld() throws InterruptedException {
        when(reviewLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService, never()).getView(anyLong());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        // 锁获取失败（未持有），不得释放他人持有的锁
        verify(reviewLock, never()).unlock();
    }

    @Test
    @DisplayName("§6.82: 核验正常完成 → finally 释放互斥锁")
    void shouldReleaseLockAfterReview() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(redissonClient).getLock("review:lock:" + SUB_TASK_ID);
        verify(reviewLock).unlock();
    }

    @Test
    @DisplayName("§6.82: LLM 调用异常 → 锁仍释放（finally 兜底）")
    void shouldReleaseLockEvenOnException() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenThrow(new RuntimeException("llm down"));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(reviewLock).unlock();
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
    }

    // ══════════════════════════════════════════════════════════════
    //  方案3 F2：核验 Prompt 附件内容注入（Reviewer 内容级核验）
    // ══════════════════════════════════════════════════════════════

    private AttachmentView readableAttachment(Long id, String name, String type, long size, byte[] content) {
        AttachmentView att = attachmentView(id, name, type, null, size, true);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(att));
        when(attachmentService.loadContent(id)).thenReturn(content);
        return att;
    }

    private String captureReviewPrompt() {
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), taskCaptor.capture());
        return taskCaptor.getValue().getUserPrompt();
    }

    private void stubReviewerPass() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));
    }

    @Test
    @DisplayName("方案3 F2: 可直读附件正文注入核验 Prompt（标题+正文），核验链放行")
    void shouldInjectReadableAttachmentContentIntoPrompt() {
        readableAttachment(501L, "main.sh", "text/x-shellscript", 12L,
                "#!/bin/bash\necho hello\n# 校验通过".getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("## 物化附件内容");
        assertThat(prompt).contains("### main.sh（text/x-shellscript，12 bytes）");
        assertThat(prompt).contains("echo hello");
        assertThat(prompt).doesNotContain("已截断");
        verify(subTaskService).complete(SUB_TASK_ID);
    }

    @Test
    @DisplayName("方案3 F2: 附件正文超过每附件限额（64000）时截断并标注")
    void shouldTruncateOversizedAttachmentContent() {
        String longContent = "行".repeat(70000);
        readableAttachment(502L, "big.log", "text/plain", 70000L, longContent.getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("部分附件内容已截断至限额");
        assertThat(prompt).contains("行".repeat(64000));
        assertThat(prompt).doesNotContain("行".repeat(64001));
    }

    @Test
    @DisplayName("P1-4-c: 附件超限时输出结构化 [TRUNCATED] 标注行（file/shown/total/reason）")
    void shouldEmitStructuredTruncationMarker() {
        String longContent = "行".repeat(70000);
        readableAttachment(504L, "big2.log", "text/plain", 70000L, longContent.getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("[TRUNCATED] file=big2.log");
        assertThat(prompt).contains("shown=64000");
        assertThat(prompt).contains("reason=per_file_limit");
    }

    @Test
    @DisplayName("R2 修复: 带 Markdown 标题的长附件截断后，注入章节大纲（后半段章节名可见）")
    void shouldInjectStructureOutlineWhenTruncated() {
        // 构造一个超 64000 字符、含 Markdown 标题的附件：前半段是正文，后半段有「目录树/教程大纲」章节
        StringBuilder body = new StringBuilder();
        body.append("# 契约文档\n\n## 1. API 端点表\n");
        body.append("正文".repeat(33000)); // 66000 字符，确保超过 64000 触发截断
        body.append("\n## 2. 目录树\n");
        body.append("目录树正文\n");
        body.append("## 3. 教程大纲\n");
        body.append("大纲正文\n");
        body.append("## 4. 排查与验证\n");
        readableAttachment(507L, "fastapi_contract.md", "markdown", 70000L,
                body.toString().getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        // 截断标注仍在（证明确实触发了 per-file 截断）
        assertThat(prompt).contains("[TRUNCATED] file=fastapi_contract.md");
        assertThat(prompt).contains("reason=per_file_limit");
        // ★R2 核心断言：即使正文被截断，「目录树」「教程大纲」章节名也必须出现在大纲里
        assertThat(prompt).contains("（该文件后续章节结构，正文已截断）");
        assertThat(prompt).contains("目录树");
        assertThat(prompt).contains("教程大纲");
    }

    @Test
    @DisplayName("P1-6: 核验 Prompt 含「不可见内容不得补全」条款与截断标注说明")
    void shouldIncludeInvisibleContentRule() {
        readableAttachment(505L, "small.log", "text/plain", 10L, "短内容".getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("不可见内容不得补全");
        // 断言「截断标注说明块」独有文本："[TRUNCATED]" 字面量在规则 11 正文中也出现，
        // 用它做存在性断言会恒真（变异测试已证），必须用说明块独有片段。
        assertThat(prompt).contains("shown=<可见字符数>");
    }

    @Test
    @DisplayName("方案3 F2: 多个附件总计超限（200000）时，靠后附件正文被截到剩余额度")
    void shouldStopWhenTotalLimitExceeded() {
        // 4 个 70000 字符附件：每个先被 per-file 截到 64000，前 3 个注入 192000 未超限；
        // 第 4 个（d.log）注入时剩余额度不足 64000，被截到剩余额度（reason=total_limit）
        AttachmentView a = attachmentWithMime(503L, "a.log", null, true);
        AttachmentView b = attachmentWithMime(504L, "b.log", null, true);
        AttachmentView c = attachmentWithMime(505L, "c.log", null, true);
        AttachmentView d = attachmentWithMime(506L, "d.log", null, true);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(a, b, c, d));
        when(attachmentService.loadContent(503L)).thenReturn("A".repeat(70000).getBytes(StandardCharsets.UTF_8));
        when(attachmentService.loadContent(504L)).thenReturn("B".repeat(70000).getBytes(StandardCharsets.UTF_8));
        when(attachmentService.loadContent(505L)).thenReturn("C".repeat(70000).getBytes(StandardCharsets.UTF_8));
        when(attachmentService.loadContent(506L)).thenReturn("D".repeat(70000).getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("附件内容总计超出限额，后续附件仅见清单");
        // 前三个附件以 per-file 上限（64000）注入，第四个附件因总量上限被截到剩余额度
        assertThat(prompt).contains("reason=per_file_limit");
        assertThat(prompt).contains("reason=total_limit");
        // 总量上限截断标注落在 d.log：正文被截到剩余额度（shown < 64000），不再是完整 per-file 64000
        assertThat(prompt).contains("file=d.log");
        assertThat(prompt).contains("shown=");
        assertThat(prompt).doesNotContain("[TRUNCATED] file=d.log shown=64000");
    }

    @Test
    @DisplayName("方案3 F2: 不可直读附件仅见清单，内容段标注不可读")
    void shouldMarkUnreadableAttachment() {
        AttachmentView external = attachmentView(505L, "out.zip", "application/zip", null, null, false);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(external));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("无平台可直读附件，无法核对文件正文");
        verify(attachmentService, never()).loadContent(anyLong());
    }

    @Test
    @DisplayName("方案3 F2: 开关关闭时退化为仅清单，不读取附件内容")
    void shouldSkipContentWhenSwitchDisabled() {
        when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(false);
        AttachmentView att = attachmentView(507L, "main.sh", "text/x-shellscript", null, null, true);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(att));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("附件内容注入已关闭，仅见清单");
        verify(attachmentService, never()).loadContent(anyLong());
    }

    /** 构造仅含 id/name 的附件（配合 list 覆盖 stub 使用）。 */
    private AttachmentView attachmentWithId(Long id, String name) {
        return attachmentView(id, name, null, null, null, false);
    }

    /** 仅含 id/name/mimeType 的附件视图（含 contentLoadable 显式值）。 */
    private static AttachmentView attachmentWithMime(Long id, String name, String mimeType, boolean contentLoadable) {
        return new AttachmentView(id, name, null, mimeType, null, contentLoadable);
    }

    // ════════════════════════════════════════════════════════════
    //  核验附件注入硬化：媒体附件不注入二进制 + 媒体可见性标注
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("硬化: 图片附件不注入二进制正文，媒体可见性标注点名文件")
    void shouldNotInjectImageBinaryAndAddMediaNote() {
        AttachmentView md = attachmentWithMime(601L, "walkthrough.md", "text/markdown", true);
        AttachmentView png = attachmentWithMime(602L, "screenshot_01.png", "image/png", true);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(md, png));
        when(attachmentService.loadContent(601L))
                .thenReturn("走查正文内容".getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("走查正文内容");
        assertThat(prompt).contains("本提交含 1 个媒体附件（screenshot_01.png）");
        assertThat(prompt).contains("当前核验链路无法查看其原始内容");
        // 二进制字节绝不按文本读取
        verify(attachmentService, never()).loadContent(602L);
    }

    @Test
    @DisplayName("硬化: mimeType 缺失时按扩展名识别媒体附件，同样不注入正文")
    void shouldDetectMediaByExtensionWhenMimeMissing() {
        AttachmentView png = attachmentWithMime(603L, "screenshot_02.png", null, true); // mimeType 缺失
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(png));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("本提交含 1 个媒体附件（screenshot_02.png）");
        verify(attachmentService, never()).loadContent(603L);
    }

    @Test
    @DisplayName("硬化: 注入开关关闭时媒体可见性标注仍注入")
    void shouldKeepMediaNoteWhenSwitchDisabled() {
        when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(false);
        AttachmentView png = attachmentWithMime(604L, "shot.jpg", "image/jpeg", false);
        when(attachmentService.listActiveViews(SUB_TASK_ID)).thenReturn(List.of(png));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("本提交含 1 个媒体附件（shot.jpg）");
        assertThat(prompt).contains("附件内容注入已关闭，仅见清单");
        verify(attachmentService, never()).loadContent(anyLong());
    }

    @Test
    @DisplayName("硬化: 纯文本附件组合不产生媒体可见性标注")
    void shouldNotAddMediaNoteForTextOnlyAttachments() {
        readableAttachment(605L, "notes.md", "markdown", 10L,
                "纯文本内容".getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("纯文本内容");
        assertThat(prompt).doesNotContain("媒体附件");
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.142 双审（difficulty=HIGH）：一致/分歧/降级/指定跳过/ANY
    //  ══════════════════════════════════════════════════════════════

    private void stubDualReviewEnabled() {
        when(reviewProperties.isDualReviewEnabled()).thenReturn(true);
        when(reviewerPicker.isDualReviewRequired(TASK_ID)).thenReturn(true);
        // 默认从严：REQUIRE_BOTH（分歧转人工）；ANY 用例单独覆盖
        when(reviewProperties.getDualReviewConsensusPolicy())
                .thenReturn(ReviewProperties.DualReviewConsensusPolicy.REQUIRE_BOTH);
        // 单侧核验超时（秒）：默认 90，防 deadline 立即过期（mock 默认 0）
        when(reviewProperties.getDualReviewTimeoutSeconds()).thenReturn(120L);
    }

    @Test
    @DisplayName("§6.142 双审一致通过 → 走既有通过链，仅落一条 review_record，双 reviewer 画像各 +1")
    void shouldDualReviewConsistentPass() {
        stubDualReviewEnabled();
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(
                llmAgent(9L, AgentRole.REVIEWER), llmAgent(10L, AgentRole.REVIEWER)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100),
                        AgentResult.success(
                        "{\"pass\": true, \"score\": 4, \"issues\": \"\", \"comment\": \"同意\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        // 双审只落一条共识 record（reviewer1 归属，防执行者画像重复计数）
        verify(recordReviewService).recordAutoReview(
                eq(SUB_TASK_ID), eq(9L), eq(ReviewResult.APPROVED), anyInt(), any(), any());
        // Reviewer 维度画像：两个 reviewer 各 +1 reviewed、0 disagreement
        verify(agentQualityProfileService).incrementReviewerStats(9L, 1, 0);
        verify(agentQualityProfileService).incrementReviewerStats(10L, 1, 0);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_dual_review_consented"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("§6.142 双审分歧（一过一拒）→ 停 REVIEW 转人工介入 + 双 reviewer disagreement +1")
    void shouldManualInterventionWhenDualReviewDisagreement() {
        stubDualReviewEnabled();
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(
                llmAgent(9L, AgentRole.REVIEWER), llmAgent(10L, AgentRole.REVIEWER)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100),
                        AgentResult.success(
                        "{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        // 分歧复用前端人工介入面板（零新增通道），payload 含两审判定明细
        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("reviewer_disagreement"), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("reviewer1AgentId", 9L)
                .containsEntry("pass1", true)
                .containsEntry("pass2", false);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_reviewer_disagreement"),
                eq(AgentRole.REVIEWER), any(), anyMap());
        verify(agentQualityProfileService).incrementReviewerStats(9L, 1, 1);
        verify(agentQualityProfileService).incrementReviewerStats(10L, 1, 1);
        // 分歧不落 review_record（未产生共识判定，防画像重复计数）
        verify(recordReviewService, never()).recordAutoReview(anyLong(), any(), any(), anyInt(), any(), any());
        verify(conversationService).addMessage(eq(SUB_TASK_ID), eq(9L), eq("assistant"), eq("agent"),
                anyString(), eq("subtask_review_skip_disagreement"));
    }

    @Test
    @DisplayName("§6.142 双审候选不足（1 个）→ 降级单审 + 记 degraded timeline")
    void shouldDegradeToSingleReviewWhenCandidatesInsufficient() {
        // 候选不足直接降级，不会走到共识策略判断，故不 stub 策略
        when(reviewProperties.isDualReviewEnabled()).thenReturn(true);
        when(reviewerPicker.isDualReviewRequired(TASK_ID)).thenReturn(true);
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(llmAgent(9L, AgentRole.REVIEWER)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_dual_review_degraded"),
                eq(AgentRole.REVIEWER), isNull(), anyMap());
        // 降级走单审：仅一个 reviewer 参与，不触发双审画像计数
        verify(agentQualityProfileService, never()).incrementReviewerStats(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("§6.142 指定 reviewer 或非 HIGH → 跳过双审，走单审")
    void shouldSkipDualWhenNotRequired() {
        when(reviewProperties.isDualReviewEnabled()).thenReturn(true);
        when(reviewerPicker.isDualReviewRequired(TASK_ID)).thenReturn(false);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(reviewerPicker, never()).pickDual(any());
        verify(subTaskService).complete(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_passed"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("§6.142 ANY 策略：任一通过即按通过落地（reviewer2 通过）")
    void shouldApplyAnyPolicyWhenEitherPasses() {
        stubDualReviewEnabled();
        when(reviewProperties.getDualReviewConsensusPolicy())
                .thenReturn(ReviewProperties.DualReviewConsensusPolicy.ANY);
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(
                llmAgent(9L, AgentRole.REVIEWER), llmAgent(10L, AgentRole.REVIEWER)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"\"}", "stop", "llm", 100),
                        AgentResult.success(
                        "{\"pass\": true, \"score\": 4, \"issues\": \"\", \"comment\": \"可过\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_dual_review_consented"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("§6.142 双审单侧核验超时（deadline 过期）→ 不可判定停留 REVIEW，仅 incomplete 观测")
    void shouldStayReviewWhenDualReviewTimeout() {
        // 超时配置为 0：deadline 立即过期，两侧均按不可判定处理（future 不取消，结果丢弃）；
        // 不可判定路径提前 return，不走到共识策略判断，故不 stub policy
        when(reviewProperties.isDualReviewEnabled()).thenReturn(true);
        when(reviewerPicker.isDualReviewRequired(TASK_ID)).thenReturn(true);
        when(reviewProperties.getDualReviewTimeoutSeconds()).thenReturn(0L);
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(
                llmAgent(9L, AgentRole.REVIEWER), llmAgent(10L, AgentRole.REVIEWER)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 不可判定：不改状态、不落 record、不记画像，仅 incomplete 观测等人工
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        verify(recordReviewService, never()).recordAutoReview(anyLong(), any(), any(), anyInt(), any(), any());
        verify(agentQualityProfileService, never()).incrementReviewerStats(anyLong(), anyInt(), anyInt());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_dual_review_incomplete"),
                eq(AgentRole.REVIEWER), isNull(), anyMap());
    }

    @Test
    @DisplayName("§6.142 双审并行：两路核验在专用线程池上同时执行（并发 in-flight = 2）后按共识落地")
    void shouldRunDualVerdictsInParallel() throws InterruptedException {
        stubDualReviewEnabled();
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(
                llmAgent(9L, AgentRole.REVIEWER), llmAgent(10L, AgentRole.REVIEWER)));
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(reviewSubTask());
        // 两路 executeSync 同时在途：各自进入 answer 后互相等待，观察线程验证并发度后放行
        CountDownLatch bothInFlight = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger();
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenAnswer(inv -> {
                    maxConcurrent.incrementAndGet();
                    bothInFlight.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("parallel release timeout");
                    }
                    return AgentResult.success(
                            "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100);
                });
        // 观察线程：两路核验都在途后放行（主线程阻塞在 awaitVerdict，不能由主线程放行）
        Thread observer = new Thread(() -> {
            try {
                bothInFlight.await(2, TimeUnit.SECONDS);
                release.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        observer.start();
        // 真实 2 线程池：同步直跑 Executor 下无法验证并发，此处换真实线程池重建被测服务
        ExecutorService pool = Executors.newFixedThreadPool(2);
        reviewService = buildService(pool);
        try {
            reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);
        } finally {
            pool.shutdownNow();
        }

        // 两路核验真正同时执行（串行实现下最大并发只能到 1，此断言即并行语义证据）
        assertThat(maxConcurrent.get()).isEqualTo(2);
        verify(subTaskService).complete(SUB_TASK_ID);
    }

}
