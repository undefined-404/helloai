package com.helloai.core.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.service.impl.SubTaskReviewServiceImpl;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewExecutionEngine;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
import java.util.concurrent.TimeUnit;

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
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ReviewerPicker reviewerPicker;

    @Mock
    private ReviewProperties reviewProperties;

    @Mock
    private AgentQualityProfileService agentQualityProfileService;

    private SubTaskReviewService reviewService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）；
        // 执行引擎/证据装配/判定解析为真实组件实例（拆分后主类仅编排，不直接持有 mock 目标）
        reviewService = new SubTaskReviewServiceImpl(
                subTaskService, agentService,
                new ReviewExecutionEngine(platformAgentExecutionService, conversationService,
                        taskTimelineService, new VerdictParser(new ObjectMapper()),
                        new ReviewEvidenceAssembler(attachmentService, dispatchProperties)),
                taskTimelineService, executionCommandService, dispatchProperties,
                conversationService, recordReviewService, redisTemplate,
                new ReviewEvidenceAssembler(attachmentService, dispatchProperties),
                new VerdictParser(new ObjectMapper()),
                reviewerPicker, reviewProperties, agentQualityProfileService);
        // §6.142 选取职责迁入 Picker：单审默认返回 9L REVIEWER（双审/指定用例单独 stub）
        lenient().when(reviewerPicker.pickSingle(any())).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
        // §6.142 双审默认关闭：既有用例保持单审语义（双审用例单独 stub true）
        lenient().when(reviewProperties.isDualReviewEnabled()).thenReturn(false);
        lenient().when(dispatchProperties.getAutoReviewMaxRework()).thenReturn(3);
        lenient().when(dispatchProperties.getReviewEvidenceCheckWaitMs()).thenReturn(0);
        // 方案3 F2 附件内容注入：默认开启（开关用例单独 stub 为 false）
        lenient().when(dispatchProperties.isAttachmentContentEnabled()).thenReturn(true);
        // §6.82 核验互斥锁：默认可获取（所有既有用例走完整核验链路）；锁用例单独 stub 为 false
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
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
        //  证据检查：默认携带可读产出（非执行密集任务 output 即产出支撑）
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        subTask.setContext(ctx);
        return subTask;
    }

    private Agent llmAgent(long id, AgentRole role) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setRole(role);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        return agent;
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
    @DisplayName("核验通过 → complete（REVIEW→DONE）并记 timeline")
    void shouldCompleteWhenVerdictPass() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺 3 个端点\", \"comment\": \"\"}", "stop", "llm", 100));
        when(agentService.getById(EXECUTOR_ID)).thenReturn(llmAgent(EXECUTOR_ID, AgentRole.EXECUTOR));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).rework(SUB_TASK_ID, EXECUTOR_ID);
        verify(subTaskService, never()).complete(anyLong());
        verify(executionCommandService).createAssignedCommand(SUB_TASK_ID, EXECUTOR_ID, "auto-review-rework");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_rejected"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("输出不可解析 → 不改状态（停留 REVIEW），记 unparseable timeline")
    void shouldStayInReviewWhenUnparseable() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        SubTask done = reviewSubTask();
        done.setStatus(SubTaskStatus.DONE);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(done);
        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        SubTask maxRework = reviewSubTask();
        maxRework.setReworkCount(3);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(maxRework);
        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_skip_max_rework"),
                eq(AgentRole.REVIEWER), any(), anyMap());
        // §6.52：返工达上限须写入人工介入标记（前端面板据此展示）
        verify(subTaskService).markManualIntervention(eq(SUB_TASK_ID), eq("rework_limit"), anyMap());
        // 核验返工熔断显式入死信（与调度维度 sub_task_dead_letter 对称），DLQ 泳道可回溯
        ArgumentCaptor<Map> deadLetterPayload = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_dead_letter"),
                eq(AgentRole.SYSTEM), isNull(), deadLetterPayload.capture());
        assertThat(deadLetterPayload.getValue())
                .containsEntry("reason", "rework_limit_exceeded")
                .containsEntry("reworkCount", 3)
                .containsEntry("maxRework", 3);
    }

    @Test
    @DisplayName("执行密集任务 + 提交者无本机能力 → 跳过自动核验 + 标记人工介入")
    void shouldSkipReviewWhenExecutionDenseSubmitterLacksCapability() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTask dense = reviewSubTask();
        dense.setContent("编写 verify-order-expire.ps1 脚本并执行验证");
        dense.setDeliverable("verify-order-expire.ps1");
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(dense);
        // 提交者：API_KEY_LLM 且无 supportsMCP（inner-loop 场景）
        when(agentService.getById(EXECUTOR_ID)).thenReturn(llmAgent(EXECUTOR_ID, AgentRole.EXECUTOR));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_execution_dense_no_capability"), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_skip_no_capability"),
                eq(AgentRole.REVIEWER), eq(EXECUTOR_ID), anyMap());
    }

    @Test
    @DisplayName("执行密集任务 + 提交者有本机能力 → 正常自动核验")
    void shouldReviewWhenExecutionDenseSubmitterHasLocalCapability() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTask dense = reviewSubTask();
        dense.setContent("编写 verify-order-expire.ps1 脚本并执行验证");
        dense.setDeliverable("verify-order-expire.ps1");
        dense.setContext(Map.of("lastExecution",
                Map.of("output", "脚本执行完成: PASS=12 FAIL=0 全绿\nVERIFICATION:\n命令: ./verify-order-expire.ps1\n输出: PASS=12 FAIL=0\n结论: 脚本真实执行通过")));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(dense);
        // 提交者：CLI_CLIENT（天然具备本机执行能力）
        Agent submitter = new Agent();
        submitter.setId(EXECUTOR_ID);
        submitter.setRole(AgentRole.EXECUTOR);
        submitter.setAccessType(AgentAccessType.CLI_CLIENT);
        when(agentService.getById(EXECUTOR_ID)).thenReturn(submitter);
        //  证据检查：执行密集任务需有可读物化附件支撑
        Attachment attachment = new Attachment();
        attachment.setId(100L);
        attachment.setSubTaskId(SUB_TASK_ID);
        attachment.setFileName("verify-order-expire.ps1");
        attachment.setFileType("other");
        attachment.setFileSize(2048L);
        attachment.setStorageUrl("local://helloai-local/1/verify-order-expire.ps1");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(attachment));
        when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
    }

    @Test
    @DisplayName("LLM 调用失败 → 不改状态（停留 REVIEW 等人工兜底）")
    void shouldStayInReviewWhenLlmFails() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"请补\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        ArgumentCaptor<com.helloai.core.task.entity.SubTask> captor =
                ArgumentCaptor.forClass(com.helloai.core.task.entity.SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        Map<String, Object> savedCtx = captor.getValue().getContext();
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
        SubTask subTask = reviewSubTask();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        ctx.put("reviewHistory", List.of(Map.of(
                "round", 1, "ts", "2026-08-01T10:00:00Z",
                "issues", "缺端点", "comment", "请补", "score", 2,
                "executorDoneIssues", List.of())));
        subTask.setContext(ctx);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 3, \"issues\": \"格式不对\", \"comment\": \"再改\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        List<?> history = (List<?>) captor.getValue().getContext().get("reviewHistory");
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
        SubTask subTask = reviewSubTask();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("lastExecution", Map.of("output", "接口清单已整理完毕，覆盖全部端点。"));
        ctx.put("lastAutoReview", Map.of(
                "reviewerAgentId", 9L,
                "issues", "缺端点", "comment", "请补", "score", 2));
        subTask.setContext(ctx);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"仍未达标\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        Map<String, Object> savedCtx = captor.getValue().getContext();
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": false, \"score\": 2, \"issues\": \"缺端点\", \"comment\": \"\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(captor.capture());
        List<?> history = (List<?>) captor.getValue().getContext().get("reviewHistory");
        Map<?, ?> first = (Map<?, ?>) history.get(0);
        Object done = first.get("executorDoneIssues");
        assertThat(done).isInstanceOf(List.class);
        assertThat((List<?>) done).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.58 P1：任务级 policy 指定 Reviewer
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.58: policy 指定 reviewerAgentId 生效（Picker 返回指定 reviewer）")
    void shouldUsePolicyReviewerWhenSpecified() {
        Agent pinned = llmAgent(99L, AgentRole.REVIEWER);
        pinned.setStatus(AgentStatus.ACTIVE);
        when(reviewerPicker.pickSingle(any())).thenReturn(pinned);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(reviewerPicker.pickSingle(any())).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        SubTask fake = reviewSubTask();
        fake.setContext(null); // 编造提交：连产出文本都没有
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(fake);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_no_evidence"),
                argThat(m -> "no_output_no_attachment".equals(m.get("reason"))));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_review_skip_no_evidence"),
                eq(AgentRole.REVIEWER), eq(EXECUTOR_ID), anyMap());
    }

    @Test
    @DisplayName("执行密集任务仅文字描述产出、无可读物化附件 → 跳过自动核验")
    void shouldSkipReviewWhenExecutionDenseWithoutReadableAttachment() {
        SubTask dense = reviewSubTask();
        dense.setContent("编写 verify-order-expire.ps1 脚本并执行验证");
        dense.setDeliverable("verify-order-expire.ps1");
        dense.setContext(Map.of("lastExecution",
                Map.of("output", "脚本已完成并执行通过: 文件 203 行 errors=0"))); // 仅文字声称，无真实附件
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(dense);
        // 附件存在但平台不可直读（外部存储）→ 不算可验证证据
        Attachment external = new Attachment();
        external.setId(100L);
        external.setSubTaskId(SUB_TASK_ID);
        external.setFileName("verify-order-expire.ps1");
        external.setStorageUrl("minio://bucket/obj");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(external));
        when(attachmentService.isContentLoadable(external)).thenReturn(false);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
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
        SubTask dense = reviewSubTask();
        dense.setContent("编写 verify-order-expire.ps1 脚本并执行验证");
        dense.setDeliverable("verify-order-expire.ps1");
        dense.setContext(Map.of("lastExecution",
                Map.of("output", "脚本执行完成: PASS=12 FAIL=0 全绿")));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(dense);
        // 无任何附件（物化缺失/失败场景，重查后仍无）
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of());

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(attachmentService, org.mockito.Mockito.times(2)).listActive(SUB_TASK_ID);
        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
        verify(subTaskService).markManualIntervention(
                eq(SUB_TASK_ID), eq("review_skip_no_evidence"),
                argThat(m -> "execution_dense_no_attachment".equals(m.get("reason"))));
    }

    @Test
    @DisplayName("核验 Prompt 注入物化附件清单（有附件列文件名 / 无附件占位）")
    void shouldInjectAttachmentListIntoReviewPrompt() {
        // 有可读附件：prompt 应含附件清单章节与文件名
        SubTask subTask = reviewSubTask();
        Attachment attachment = new Attachment();
        attachment.setId(100L);
        attachment.setSubTaskId(SUB_TASK_ID);
        attachment.setFileName("api-docs.md");
        attachment.setFileType("markdown");
        attachment.setFileSize(1024L);
        attachment.setStorageUrl("local://helloai-local/1/api-docs.md");
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(attachment));
        when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 4, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
        String prompt = taskCaptor.getValue().getUserPrompt();
        assertThat(prompt).contains("## 物化附件清单");
        assertThat(prompt).contains("api-docs.md");
        assertThat(prompt).contains("平台可直读");
        assertThat(prompt).contains("声称的交付物必须与**物化附件清单**对应");
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.82 批次 D：核验互斥锁（防 L1/L2/L3 三路并发双审）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.82: 已有核验进行中（锁被占用）→ 跳过，不调 LLM、不改状态、不释放他人锁")
    void shouldSkipWhenReviewLockHeld() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService, never()).getById(anyLong());
        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
        // 锁未持有成功，不得删除他人持有的锁
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("§6.82: 核验正常完成 → finally 释放互斥锁")
    void shouldReleaseLockAfterReview() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(subTaskService).complete(SUB_TASK_ID);
        verify(redisTemplate).delete("review:lock:" + SUB_TASK_ID);
    }

    @Test
    @DisplayName("§6.82: LLM 调用异常 → 锁仍释放（finally 兜底）")
    void shouldReleaseLockEvenOnException() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenThrow(new RuntimeException("llm down"));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        verify(redisTemplate).delete("review:lock:" + SUB_TASK_ID);
        verify(subTaskService, never()).complete(anyLong());
        verify(subTaskService, never()).rework(anyLong(), any());
    }

    // ══════════════════════════════════════════════════════════════
    //  方案3 F2：核验 Prompt 附件内容注入（Reviewer 内容级核验）
    // ══════════════════════════════════════════════════════════════

    private Attachment readableAttachment(Long id, String name, String type, long size, byte[] content) {
        Attachment att = new Attachment();
        att.setId(id);
        att.setFileName(name);
        att.setFileType(type);
        att.setFileSize(size);
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(att));
        when(attachmentService.isContentLoadable(att)).thenReturn(true);
        when(attachmentService.loadContent(id)).thenReturn(content);
        return att;
    }

    private String captureReviewPrompt() {
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
        return taskCaptor.getValue().getUserPrompt();
    }

    private void stubReviewerPass() {
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
    @DisplayName("方案3 F2: 附件正文超过每附件限额（8000）时截断并标注")
    void shouldTruncateOversizedAttachmentContent() {
        String longContent = "行".repeat(12000);
        readableAttachment(502L, "big.log", "text/plain", 24000L, longContent.getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("部分附件内容已截断至限额");
        assertThat(prompt).contains("行".repeat(8000));
        assertThat(prompt).doesNotContain("行".repeat(8001));
    }

    @Test
    @DisplayName("方案3 F2: 多个附件总计超限（24000）时停止注入后续附件正文")
    void shouldStopWhenTotalLimitExceeded() {
        // 4 个 10000 字符附件：前 3 个吃满总限 24000，第 4 个不再注入正文
        Attachment a = attachmentWithId(503L, "a.log");
        Attachment b = attachmentWithId(504L, "b.log");
        Attachment c = attachmentWithId(505L, "c.log");
        Attachment d = attachmentWithId(506L, "d.log");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(a, b, c, d));
        for (Attachment att : List.of(a, b, c, d)) {
            when(attachmentService.isContentLoadable(att)).thenReturn(true);
        }
        when(attachmentService.loadContent(503L)).thenReturn("A".repeat(10000).getBytes(StandardCharsets.UTF_8));
        when(attachmentService.loadContent(504L)).thenReturn("B".repeat(10000).getBytes(StandardCharsets.UTF_8));
        when(attachmentService.loadContent(505L)).thenReturn("C".repeat(10000).getBytes(StandardCharsets.UTF_8));
        when(attachmentService.loadContent(506L)).thenReturn("D".repeat(10000).getBytes(StandardCharsets.UTF_8));
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("附件内容总计超出限额，后续附件仅见清单");
        assertThat(prompt).contains("### a.log").contains("### b.log").contains("### c.log");
        // 清单仍全量展示 d.log，但内容段不注入其正文
        assertThat(prompt).contains("- d.log");
        assertThat(prompt).doesNotContain("### d.log");
    }

    @Test
    @DisplayName("方案3 F2: 不可直读附件仅见清单，内容段标注不可读")
    void shouldMarkUnreadableAttachment() {
        Attachment external = new Attachment();
        external.setId(505L);
        external.setFileName("out.zip");
        external.setFileType("application/zip");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(external));
        when(attachmentService.isContentLoadable(external)).thenReturn(false);
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
        Attachment att = new Attachment();
        att.setId(507L);
        att.setFileName("main.sh");
        att.setFileType("text/x-shellscript");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(att));
        when(attachmentService.isContentLoadable(att)).thenReturn(true);
        stubReviewerPass();

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        String prompt = captureReviewPrompt();
        assertThat(prompt).contains("附件内容注入已关闭，仅见清单");
        verify(attachmentService, never()).loadContent(anyLong());
    }

    /** 构造仅含 id/name 的附件（配合 list 覆盖 stub 使用）。 */
    private Attachment attachmentWithId(Long id, String name) {
        Attachment att = new Attachment();
        att.setId(id);
        att.setFileName(name);
        return att;
    }

    // ════════════════════════════════════════════════════════════
    //  核验附件注入硬化：媒体附件不注入二进制 + 媒体可见性标注
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("硬化: 图片附件不注入二进制正文，媒体可见性标注点名文件")
    void shouldNotInjectImageBinaryAndAddMediaNote() {
        Attachment md = attachmentWithId(601L, "walkthrough.md");
        md.setMimeType("text/markdown");
        Attachment png = attachmentWithId(602L, "screenshot_01.png");
        png.setMimeType("image/png");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(md, png));
        when(attachmentService.isContentLoadable(md)).thenReturn(true);
        when(attachmentService.isContentLoadable(png)).thenReturn(true);
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
        Attachment png = attachmentWithId(603L, "screenshot_02.png"); // mimeType 缺失
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(png));
        when(attachmentService.isContentLoadable(png)).thenReturn(true);
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
        Attachment png = attachmentWithId(604L, "shot.jpg");
        png.setMimeType("image/jpeg");
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(png));
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
    }

    @Test
    @DisplayName("§6.142 双审一致通过 → 走既有通过链，仅落一条 review_record，双 reviewer 画像各 +1")
    void shouldDualReviewConsistentPass() {
        stubDualReviewEnabled();
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(
                llmAgent(9L, AgentRole.REVIEWER), llmAgent(10L, AgentRole.REVIEWER)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
    }

    @Test
    @DisplayName("§6.142 双审候选不足（1 个）→ 降级单审 + 记 degraded timeline")
    void shouldDegradeToSingleReviewWhenCandidatesInsufficient() {
        // 候选不足直接降级，不会走到共识策略判断，故不 stub 策略
        when(reviewProperties.isDualReviewEnabled()).thenReturn(true);
        when(reviewerPicker.isDualReviewRequired(TASK_ID)).thenReturn(true);
        when(reviewerPicker.pickDual(any())).thenReturn(List.of(llmAgent(9L, AgentRole.REVIEWER)));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
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

}
