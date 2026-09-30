package com.helloai.core.agent.mqconsumer;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.AgentRuntime;
import com.helloai.core.agent.runtime.ExecutionEnvironmentProvider;
import com.helloai.core.agent.runtime.LocalProcessEnvironment;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentRuntimeContextAssembler;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.SubTaskExecutionService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LocalExecutionCommandConsumer} 单测（G-002 单轨分层编排）。
 *
 * <p>覆盖编排：startIfNeeded 状态推进 → record CAS → 消费 timeline → 上下文装配 →
 * 真身 execute + afterTurn 会话推进 → record CAS 终态 → ExecutionResultHandler 回写；
 * 以及三处契约化失败路径（状态推进失败 / 真身 FAILED / 真身抛异常）。</p>
 */
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

    /** Phase 1 Step 2：启用工具解析（agent_mcp_server），消费侧 agent 域直读注入 ctx.tools。 */
    @Mock
    private AgentMcpServerService agentMcpServerService;

    /** Phase 1 Step 4：执行环境解析（agent.accessType），消费侧 agent 域解析注入 ctx.environment。 */
    @Mock
    private ExecutionEnvironmentProvider executionEnvironmentProvider;

    /** G-002 单轨：状态推进（幂等前置）。 */
    @Mock
    private SubTaskExecutionService subTaskExecutionService;

    /** G-002 单轨：Runtime 真身上下文装配（prompt / chatModel / 会话）。 */
    @Mock
    private AgentRuntimeContextAssembler contextAssembler;

    /** G-002 单轨：结果回写（成功 submit → REVIEW / 失败 block）。 */
    @Mock
    private ExecutionResultHandler executionResultHandler;

    @Mock
    private AgentRuntime agentRuntime;

    @InjectMocks
    private LocalExecutionCommandConsumer localExecutionCommandConsumer;

    @Nested
    @DisplayName("Runtime 真身分层编排（G-002 单轨唯一执行入口）")
    class RuntimeExecutionPath {

        @Test
        @DisplayName("should run startIfNeeded + assemble + runtime.execute + afterTurn + handleSuccess")
        void shouldExecuteViaRuntimeAndMarkSuccess() {
            setAgentRuntime();
            SubTask subTask = subTask();
            Agent agent = agent();
            agent.setAccessType(AgentAccessType.API_KEY_LLM);
            AgentContext ctx = assembledContext();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markSuccess(44L)).thenReturn(true);
            // Phase 1 Step 2/4：工具 / 环境由消费侧 agent 域解析注入装配器
            when(agentMcpServerService.getEnabledTools(11L))
                    .thenReturn(List.of("pullTasks", "submitResult"));
            when(executionEnvironmentProvider.resolve(AgentAccessType.API_KEY_LLM))
                    .thenReturn(new LocalProcessEnvironment());
            // G-002：装配器产出完整上下文，真身 execute 接收同一对象
            when(contextAssembler.assemble(any(), any(), any(), any(), any())).thenReturn(ctx);
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenReturn(AgentExecutionResult.builder()
                            .status(ExecutionStatus.SUCCESS)
                            .output("out")
                            .finishReason("STOP")
                            .build());

            localExecutionCommandConsumer.consume(baseCommand());

            // 编排：状态推进 → 装配 → 真身执行 → 会话推进
            verify(subTaskExecutionService).startIfNeeded(22L, SubTaskStatus.ASSIGNED);
            verify(contextAssembler).assemble(any(), same(subTask), same(agent), any(), any());
            verify(agentRuntime).execute(same(ctx));
            verify(contextAssembler).afterTurn(same(subTask), same(agent), eq(1), any());
            // record CAS 由消费侧代理执行
            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markSuccess(44L);
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
            // 回写：AgentResult 映射（成功 + executorName=RuntimeTurnExecutor）
            verify(executionResultHandler).handleSuccess(eq(22L), eq(11L), argThat(r ->
                    r.isSuccess()
                            && "out".equals(r.getOutput())
                            && "STOP".equals(r.getFinishReason())
                            && "RuntimeTurnExecutor".equals(r.getExecutorName())));
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
        @DisplayName("should markFailed + handleFailure when runtime returns FAILED")
        void shouldMarkFailedWhenRuntimeReturnsFailed() {
            setAgentRuntime();
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markFailed(44L, "run failed")).thenReturn(true);
            when(contextAssembler.assemble(any(), any(), any(), any(), any()))
                    .thenReturn(assembledContext());
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenReturn(AgentExecutionResult.builder()
                            .status(ExecutionStatus.FAILED)
                            .output("run failed")
                            .finishReason("ERROR")
                            .build());

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markFailed(44L, "run failed");
            verify(agentExecutionRecordService, never()).markSuccess(any());
            // 失败回写：handleFailure（BizException 承载失败正文）
            verify(executionResultHandler).handleFailure(eq(22L), eq(11L), argThat(e -> e instanceof RuntimeException));
            verify(executionResultHandler, never()).handleSuccess(any(), any(), any());
        }

        @Test
        @DisplayName("should markFailed + handleFailure when runtime execute throws (防御违约实现)")
        void shouldMarkFailedWhenRuntimeThrows() {
            setAgentRuntime();
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markFailed(44L, "boom")).thenReturn(true);
            when(contextAssembler.assemble(any(), any(), any(), any(), any()))
                    .thenReturn(assembledContext());
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenThrow(new IllegalStateException("boom"));

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markFailed(44L, "boom");
            verify(agentExecutionRecordService, never()).markSuccess(any());
            verify(executionResultHandler).handleFailure(eq(22L), eq(11L), any());
        }

        @Test
        @DisplayName("should markFailed + handleFailure when startIfNeeded throws (状态推进失败契约化)")
        void shouldMarkFailedWhenStartIfNeededThrows() {
            setAgentRuntime();
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.REVIEW); // 不允许执行：startIfNeeded 抛 BizException
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markFailed(44L, "sub task not allowed")).thenReturn(true);
            doThrow(new IllegalStateException("sub task not allowed"))
                    .when(subTaskExecutionService).startIfNeeded(22L, SubTaskStatus.REVIEW);

            localExecutionCommandConsumer.consume(baseCommand());

            // 状态推进失败：不进入执行阶段，record CAS 终态 + 回写失败
            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(agentExecutionRecordService).markFailed(44L, "sub task not allowed");
            verify(agentRuntime, never()).execute(any(AgentContext.class));
            verify(executionResultHandler).handleFailure(eq(22L), eq(11L), any());
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

            // IN_PROGRESS 幂等跳过状态推进；markRunning CAS 失败即放弃，不进入执行
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

    /** 装配器产出的最小完整上下文（run_id / turn 与 B2 埋点同源：run-{taskId}-1 / turn=1）。 */
    private static AgentContext assembledContext() {
        return AgentContext.builder()
                .runId("run-33-1")
                .taskId(33L)
                .subTaskId(22L)
                .turn(1)
                .step(0)
                .agentId(11L)
                .skills(List.of("eng-code-review", "eng-doc-standard"))
                .tools(List.of("pullTasks", "submitResult"))
                .environment(new LocalProcessEnvironment())
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();
    }

    private static SubTask subTask() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        return subTask;
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setName("test-agent");
        return agent;
    }

    /**
     * Phase 1 Step 1 fix：requiredSkills 由命令装箱（等价于 task.requiredSkills 装箱后的值，
     * 消费侧不再反向查询 task；生产代码由 4 处 createAssignedCommand 调用方装箱）。
     */
    private static ExecutionCommand baseCommand() {
        return ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-1")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .requiredSkills(List.of("eng-code-review", "eng-doc-standard"))
                .build();
    }
}