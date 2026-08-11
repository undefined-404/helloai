package com.helloai.core.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.command.ExecutionCommandService;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskAgentPolicy;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubTaskReviewService 单元测试（V27 核验门控）：
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
    private AgentSelector agentSelector;

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
    private TaskService taskService;

    private SubTaskReviewService reviewService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        reviewService = new SubTaskReviewService(
                subTaskService, agentSelector, agentService, platformAgentExecutionService,
                taskTimelineService, executionCommandService, dispatchProperties, new ObjectMapper(),
                conversationService, recordReviewService, taskService);
        lenient().when(dispatchProperties.getAutoReviewMaxRework()).thenReturn(3);
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
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
    }

    @Test
    @DisplayName("V27.1: 执行密集任务 + 提交者无本机能力 → 跳过自动核验 + 标记人工介入")
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
    @DisplayName("V27.1: 执行密集任务 + 提交者有本机能力 → 正常自动核验")
    void shouldReviewWhenExecutionDenseSubmitterHasLocalCapability() {
        when(dispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        SubTask dense = reviewSubTask();
        dense.setContent("编写 verify-order-expire.ps1 脚本并执行验证");
        dense.setDeliverable("verify-order-expire.ps1");
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(dense);
        // 提交者：CLI_CLIENT（天然具备本机执行能力）
        Agent submitter = new Agent();
        submitter.setId(EXECUTOR_ID);
        submitter.setRole(AgentRole.EXECUTOR);
        submitter.setAccessType(AgentAccessType.CLI_CLIENT);
        when(agentService.getById(EXECUTOR_ID)).thenReturn(submitter);
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        ctx.put("reviewHistory", List.of(Map.of(
                "round", 1, "ts", "2026-08-01T10:00:00Z",
                "issues", "缺端点", "comment", "请补", "score", 2,
                "executorDoneIssues", List.of())));
        subTask.setContext(ctx);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        ctx.put("lastAutoReview", Map.of(
                "reviewerAgentId", 9L,
                "issues", "缺端点", "comment", "请补", "score", 2));
        subTask.setContext(ctx);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
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
    //  V47 §6.58 P1：任务级 policy 指定 Reviewer
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("V47 §6.58: policy 指定 reviewerAgentId 优先于自动选择")
    void shouldUsePolicyReviewerWhenSpecified() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setAgentPolicy(TaskAgentPolicy.build(null, null, 99L, null, null));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(taskService.getById(TASK_ID)).thenReturn(task);
        Agent pinned = llmAgent(99L, AgentRole.REVIEWER);
        pinned.setStatus(AgentStatus.ACTIVE);
        when(agentService.getById(99L)).thenReturn(pinned);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 指定可用 → 不再走自动选择链
        verify(agentSelector, never()).pickPreferred(any());
        verify(agentSelector, never()).pickPreferred(any(), any());
        verify(subTaskService).complete(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_passed"),
                eq(AgentRole.REVIEWER), eq(99L), anyMap());
    }

    @Test
    @DisplayName("V47 §6.58: 指定 reviewer 不可用（DISABLED）→ 回退自动选择")
    void shouldFallbackToAutoWhenPolicyReviewerUnusable() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setAgentPolicy(TaskAgentPolicy.build(null, null, 99L, null, null));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(reviewSubTask());
        when(taskService.getById(TASK_ID)).thenReturn(task);
        Agent disabled = llmAgent(99L, AgentRole.REVIEWER);
        disabled.setStatus(AgentStatus.DISABLED);
        when(agentService.getById(99L)).thenReturn(disabled);
        when(agentSelector.pickPreferred(AgentRole.REVIEWER)).thenReturn(llmAgent(9L, AgentRole.REVIEWER));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"pass\": true, \"score\": 5, \"issues\": \"\", \"comment\": \"ok\"}", "stop", "llm", 100));

        reviewService.reviewSubTask(SUB_TASK_ID, EXECUTOR_ID);

        // 指定失效 → 走自动选择链的 REVIEWER
        verify(subTaskService).complete(SUB_TASK_ID);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_auto_review_passed"),
                eq(AgentRole.REVIEWER), eq(9L), anyMap());
    }
}
