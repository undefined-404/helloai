package com.helloai.core.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
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
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
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

    private SubTaskReviewService reviewService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        reviewService = new SubTaskReviewService(
                subTaskService, agentSelector, agentService, platformAgentExecutionService,
                taskTimelineService, executionCommandService, dispatchProperties, new ObjectMapper(),
                conversationService, recordReviewService);
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
}
