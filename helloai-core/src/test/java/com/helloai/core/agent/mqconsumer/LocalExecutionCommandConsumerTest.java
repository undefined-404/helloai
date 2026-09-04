package com.helloai.core.agent.mqconsumer;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.AgentRuntime;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.shared.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocalExecutionCommandConsumer")
class LocalExecutionCommandConsumerTest {

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private AgentRuntime agentRuntime;

    /** Phase 1 T1：契约供电所需 TaskService（与生产代码 LocalExecutionCommandConsumer 新增注入对齐）。 */
    @Mock
    private TaskService taskService;

    @InjectMocks
    private LocalExecutionCommandConsumer localExecutionCommandConsumer;

    @Nested
    @DisplayName("统一 AgentRuntime 执行路径（Phase 0 C3 Step 5/6 后唯一执行契约）")
    class RuntimeExecutionPath {

        @Test
        @DisplayName("should run record CAS + timeline + runtime.execute + markSuccess")
        void shouldExecuteViaRuntimeAndMarkSuccess() {
            setAgentRuntime();
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            // Phase 1 T1：契约供电单测断言——AgentContext.skills == task.requiredSkills
            when(taskService.getById(33L)).thenReturn(task());
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markSuccess(44L)).thenReturn(true);
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenReturn(AgentExecutionResult.builder()
                            .status(ExecutionStatus.SUCCESS)
                            .output("out")
                            .build());

            localExecutionCommandConsumer.consume(baseCommand());

            // AgentContext 与 B2 埋点同源：run-{taskId}-1 / turn=1 / step=0
            // Phase 1 T1：新增 skills 断言（与 task.requiredSkills 同表示）
            verify(agentRuntime).execute(argThat(ctx ->
                    ctx != null
                            && "run-33-1".equals(ctx.getRunId())
                            && ctx.getTaskId() != null && ctx.getTaskId() == 33L
                            && ctx.getSubTaskId() != null && ctx.getSubTaskId() == 22L
                            && ctx.getTurn() == 1
                            && ctx.getStep() == 0
                            && ctx.getAgentId() != null && ctx.getAgentId() == 11L
                            && ctx.getSkills() != null
                            && ctx.getSkills().equals(task().getRequiredSkills())));
            // record CAS 由消费侧代理执行
            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markSuccess(44L);
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
            // timeline 观察点（route=agent_runtime 标记契约路径）
            verify(taskTimelineService).recordEvent(
                    eq(33L), eq(22L), eq("sub_task_execution_command_consume"),
                    eq(AgentRole.EXECUTOR), eq(11L),
                    argThat((Map<String, Object> m) -> "agent_runtime".equals(m.get("route"))));
            verify(taskTimelineService).recordEvent(
                    33L, 22L, "sub_task_execute_start", AgentRole.EXECUTOR, 11L,
                    Map.of("executor", "agent_runtime"));
        }

        @Test
        @DisplayName("should markFailed when runtime returns FAILED")
        void shouldMarkFailedWhenRuntimeReturnsFailed() {
            setAgentRuntime();
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markFailed(44L, "run failed")).thenReturn(true);
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenReturn(AgentExecutionResult.builder()
                            .status(ExecutionStatus.FAILED)
                            .output("run failed")
                            .build());

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markFailed(44L, "run failed");
            verify(agentExecutionRecordService, never()).markSuccess(any());
        }

        @Test
        @DisplayName("should markFailed when runtime execute throws (防御违约实现)")
        void shouldMarkFailedWhenRuntimeThrows() {
            setAgentRuntime();
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markFailed(44L, "boom")).thenReturn(true);
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenThrow(new IllegalStateException("boom"));

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markFailed(44L, "boom");
            verify(agentExecutionRecordService, never()).markSuccess(any());
        }
    }

    @Nested
    @DisplayName("跳过路径")
    class SkipPath {

        @Test
        @DisplayName("should skip execution when markRunning returns false")
        void shouldSkipExecutionWhenMarkRunningReturnsFalse() {
            setAgentRuntime();
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(false);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentRuntime, never()).execute(any(AgentContext.class));
            verify(agentExecutionRecordService, never()).markSuccess(any());
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("should skip when no AgentRuntime implementation registered (防御装配异常)")
        void shouldSkipWhenNoAgentRuntime() {
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            // agentRuntimes = null（构造器注入失败场景）与空列表同语义：防御跳过

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentRuntime, never()).execute(any(AgentContext.class));
            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(agentExecutionRecordService, never()).markSuccess(any());
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("should skip when subTask does not exist")
        void shouldSkipWhenSubTaskNotExist() {
            when(subTaskService.getById(22L)).thenReturn(null);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(agentRuntime, never()).execute(any(AgentContext.class));
        }

        @Test
        @DisplayName("should skip when agentId does not match assignedAgent")
        void shouldSkipWhenAgentIdMismatch() {
            SubTask subTask = subTask();
            subTask.setAssignedAgentId(99L); // 与 command.agentId=11L 不一致

            when(subTaskService.getById(22L)).thenReturn(subTask);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(agentRuntime, never()).execute(any(AgentContext.class));
        }

        @Test
        @DisplayName("should skip when agent does not exist")
        void shouldSkipWhenAgentNotExist() {
            SubTask subTask = subTask();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(null);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(agentRuntime, never()).execute(any(AgentContext.class));
        }
    }

    private void setAgentRuntime() {
        ReflectionTestUtils.setField(localExecutionCommandConsumer, "agentRuntimes", List.of(agentRuntime));
    }

    private static SubTask subTask() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        return subTask;
    }

    /** Phase 1 T1：契约供电单测用 task（含 requiredSkills，验证 AgentContext.skills 赋值）。 */
    private static Task task() {
        Task task = new Task();
        task.setId(33L);
        task.setRequiredSkills(List.of("eng-code-review", "eng-doc-standard"));
        return task;
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setName("test-agent");
        return agent;
    }

    private static ExecutionCommand baseCommand() {
        return ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-1")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();
    }
}