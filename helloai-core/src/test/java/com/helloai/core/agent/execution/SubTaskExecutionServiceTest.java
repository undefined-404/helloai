package com.helloai.core.agent.execution;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.TaskRunningSpecService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskExecutionService")
class SubTaskExecutionServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ExecutionResultHandler executionResultHandler;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @Mock
    private ConversationService conversationService;  // §6.41

    @InjectMocks
    private SubTaskExecutionService subTaskExecutionService;

    @Nested
    @DisplayName("executeOnce — 纯执行入口")
    class ExecuteOnce {

        @Test
        @DisplayName("should not call startIfNeeded when executeOnce runs")
        void shouldNotCallStartIfNeededWhenExecuteOnceRuns() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            AgentResult ok = AgentResult.builder().success(true).build();
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(ok);

            AgentResult result = subTaskExecutionService.executeOnce(subTask, agent);

            assertThat(result).isSameAs(ok);
            // 纯执行：不应调用状态推进、不应回写
            verify(subTaskService, never()).start(any());
            verify(executionResultHandler, never()).handleSuccess(any(), any(), any());
            verify(executionResultHandler, never()).handleFailure(any(), any(), any());
        }

        @Test
        @DisplayName("should propagate exception without calling handleFailure when executeOnce throws")
        void shouldPropagateExceptionWithoutHandleFailureWhenExecuteOnceThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            RuntimeException root = new RuntimeException("llm down");
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenThrow(root);

            assertThatThrownBy(() -> subTaskExecutionService.executeOnce(subTask, agent))
                    .isSameAs(root);

            // 纯执行：异常直接传播，不应回写
            verify(executionResultHandler, never()).handleFailure(any(), any(), any());
        }

        @Test
        @DisplayName("should reject when subTask status is DONE")
        void shouldRejectWhenSubTaskStatusIsDone() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.DONE);
            Agent agent = agent();

            assertThatThrownBy(() -> subTaskExecutionService.executeOnce(subTask, agent))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可执行");
        }
    }

    @Nested
    @DisplayName("executeCommand — 完整编排入口（向后兼容）")
    class ExecuteCommand {

        @Test
        @DisplayName("should call startIfNeeded + handleFailure when executeSync throws")
        void shouldPropagateOriginalExceptionWhenExecuteSyncThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.ASSIGNED);
            Agent agent = agent();

            RuntimeException root = new RuntimeException();
            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(44L)).thenReturn(agent);
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenThrow(root);

            ExecutionCommand command = ExecutionCommand.builder()
                    .subTaskId(22L)
                    .agentId(44L)
                    .trigger("test")
                    .build();

            assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                    .isSameAs(root);

            verify(subTaskService).start(22L);
            verify(executionResultHandler).handleFailure(22L, 44L, root);
        }

        @Test
        @DisplayName("should throw BizException when agentId mismatch")
        void shouldThrowWhenAgentIdMismatch() {
            SubTask subTask = subTask();
            subTask.setAssignedAgentId(44L);

            when(subTaskService.getById(22L)).thenReturn(subTask);

            ExecutionCommand command = ExecutionCommand.builder()
                    .subTaskId(22L)
                    .agentId(99L)
                    .trigger("test")
                    .build();

            assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                    .isInstanceOf(BizException.class);
        }

        @Test
        @DisplayName("should throw BizException when agentId is null")
        void shouldThrowWhenCommandAgentIdIsNull() {
            ExecutionCommand command = ExecutionCommand.builder()
                    .subTaskId(22L)
                    .agentId(null)
                    .trigger("test")
                    .build();

            assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("agentId");
        }
    }

    @Nested
    @DisplayName("executeOnce — 依赖产出上下文注入（V35）")
    class ExecuteOnceDependencyContext {

        @Test
        @DisplayName("should not query deps when subTask has no dependsOn")
        void shouldNotQueryDepsWhenNoDependsOn() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            verify(subTaskService, never()).listByIds(any());
        }

        @Test
        @DisplayName("should inject upstream outputs into userPrompt when deps exist")
        void shouldInjectUpstreamOutputsIntoUserPromptWhenDepsExist() {
            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setTitle("调研竞品");
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(Map.of("lastExecution", Map.of("output", "竞品清单：A/B/C")));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            assertThat(prompt)
                    .contains("## 上游产出参考")
                    .contains("### 前置 1：调研竞品（状态：DONE）")
                    .contains("竞品清单：A/B/C");
            // 声明了依赖 → 必须记录可观测事件
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_deps_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), any(Map.class));
        }

        @Test
        @DisplayName("should render placeholder when upstream is DONE but has no output")
        void shouldRenderPlaceholderWhenUpstreamHasNoOutput() {
            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(null);

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("（该前置子任务无可用产出内容）");
        }

        @Test
        @DisplayName("should truncate oversized upstream output with explicit mark")
        void shouldTruncateOversizedUpstreamOutput() {
            String longOutput = "X".repeat(5000);
            SubTask upstream = subTask();
            upstream.setId(11L);
            upstream.setStatus(SubTaskStatus.DONE);
            upstream.setContext(Map.of("lastExecution", Map.of("output", longOutput)));

            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(upstream));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("已截断至 4000 字符")
                    .doesNotContain("X".repeat(4999));
        }

        @Test
        @DisplayName("should degrade gracefully and keep executing when dep query fails")
        void shouldDegradeGracefullyWhenDepQueryFails() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            subTask.setDependsOn(List.of(11L));
            Agent agent = agent();

            when(subTaskService.listByIds(any())).thenThrow(new RuntimeException("db down"));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            AgentResult result = subTaskExecutionService.executeOnce(subTask, agent);

            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            // 降级：不注入产出段，行为与旧版一致
            assertThat(taskCaptor.getValue().getUserPrompt()).doesNotContain("## 上游产出参考");
            // 但仍需可观测：degraded=true 进 timeline
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
            verify(taskTimelineService).recordEvent(eq(33L), eq(22L), eq("sub_task_deps_context_loaded"),
                    eq(AgentRole.EXECUTOR), eq(44L), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .containsEntry("depCount", 1)
                    .containsEntry("degraded", true);
        }
    }

    @Nested
    @DisplayName("startIfNeeded — 状态推进前置")
    class StartIfNeeded {

        @Test
        @DisplayName("should skip when status is IN_PROGRESS")
        void shouldSkipWhenStatusInProgress() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.IN_PROGRESS);
            verify(subTaskService, never()).start(any());
        }

        @Test
        @DisplayName("should call subTaskService.start when status is ASSIGNED")
        void shouldCallStartWhenAssigned() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.ASSIGNED);
            verify(subTaskService).start(22L);
        }

        @Test
        @DisplayName("should call subTaskService.start when status is REWORK")
        void shouldCallStartWhenRework() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.REWORK);
            verify(subTaskService).start(22L);
        }

        @Test
        @DisplayName("should call subTaskService.start when status is PAUSED")
        void shouldCallStartWhenPaused() {
            subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.PAUSED);
            verify(subTaskService).start(22L);
        }

        @Test
        @DisplayName("should throw when status is BLOCKED")
        void shouldThrowWhenStatusBlocked() {
            assertThatThrownBy(() -> subTaskExecutionService.startIfNeeded(22L, SubTaskStatus.BLOCKED))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不允许执行");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.41 executeOnce — 执行请求对话流 user prompt 落库 + reviewHistory 多轮铺开
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("executeOnce — §6.41 执行请求对话流 + reviewHistory 多轮铺开")
    class ExecuteOnceUserPromptAndReworkHistory {

        @Test
        @DisplayName("TC-1 should write user prompt to conversation stream with sub_task_execute_user_prompt")
        void shouldWriteUserPromptToConversationStream() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            verify(conversationService).addMessage(
                    eq(22L), eq(44L),
                    eq("user"), eq("agent"),
                    anyString(),
                    eq("sub_task_execute_user_prompt"));
        }

        @Test
        @DisplayName("TC-2 should keep user prompt in stream when executeSync throws")
        void shouldKeepUserPromptWhenExecuteSyncThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenThrow(new RuntimeException("llm down"));

            assertThatThrownBy(() -> subTaskExecutionService.executeOnce(subTask, agent))
                    .hasMessage("llm down");

            // 失败路径：prompt 仍落库（先 addMessage 后 executeSync）
            verify(conversationService).addMessage(
                    eq(22L), eq(44L),
                    eq("user"), eq("agent"),
                    anyString(),
                    eq("sub_task_execute_user_prompt"));
        }

        @Test
        @DisplayName("TC-3 should render review history with multiple rounds")
        void shouldRenderReviewHistoryWithMultipleRounds() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("reviewHistory", List.of(
                    Map.of("round", 1, "ts", "2026-08-01T10:00:00Z",
                            "issues", List.of("缺端点"), "comment", "请补", "score", 2),
                    Map.of("round", 2, "ts", "2026-08-01T11:00:00Z",
                            "issues", List.of("格式不对", "示例缺失"), "comment", "再改", "score", 3)
            ));
            subTask.setContext(ctx);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            assertThat(prompt)
                    .contains("## 返工修正指引（共 2 轮历史审核）")
                    .contains("### 第 1 轮")
                    .contains("- 时间: 2026-08-01T10:00:00Z")
                    .contains("缺端点")
                    .contains("### 第 2 轮")
                    .contains("格式不对")
                    .contains("示例缺失")
                    .contains("再改")
                    .contains("请务必针对未自认修复的问题继续修正");
        }

        @Test
        @DisplayName("TC-4 should skip rework section when reviewHistory and lastAutoReview both empty")
        void shouldSkipReworkSectionWhenHistoryEmpty() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            // 不设 context，appendReworkContext 第一行 if (ctx == null) return
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .doesNotContain("返工修正指引");
        }

        @Test
        @DisplayName("TC-5 should inject rework section from legacy lastAutoReview when reviewHistory absent")
        void shouldInjectReworkFromLegacyLastAutoReview() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("lastAutoReview", Map.of("issues", "缺端点", "comment", "请补", "score", 2));
            subTask.setContext(ctx);
            Agent agent = agent();

            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.builder().success(true).build());

            subTaskExecutionService.executeOnce(subTask, agent);

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            String prompt = taskCaptor.getValue().getUserPrompt();
            assertThat(prompt)
                    .contains("## 返工修正指引（共 1 轮历史审核）")
                    .contains("### 第 1 轮")
                    .contains("缺端点");
        }
    }

    private static SubTask subTask() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(44L);
        subTask.setTitle("demo");
        subTask.setContent("demo content");
        return subTask;
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(44L);
        agent.setName("test-agent");
        return agent;
    }
}