package com.helloai.core.agent.runtime;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.service.SubTaskExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧 Executor 适配器单元测试（Phase 0 C2）：
 * 委托 executeCommand 的契约翻译（成功 / 失败结果 / 业务校验失败 / 入参缺失）。纯 Mockito 测试不触及装配开关。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LegacyExecutorAdapter")
class LegacyExecutorAdapterTest {

    @Mock
    private SubTaskExecutionService subTaskExecutionService;

    private LegacyExecutorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LegacyExecutorAdapter(subTaskExecutionService);
    }

    @Test
    @DisplayName("成功委托：executeCommand 成功 → SUCCESS + output 透传 + command 携带 subTaskId/agentId/trigger")
    void shouldDelegateToExecuteCommandAndMapSuccess() {
        when(subTaskExecutionService.executeCommand(anyCommand())).thenReturn(AgentResult.success("done", "stop", "ApiKeyAgentExecutor", 12));

        AgentExecutionResult result = adapter.execute(ctx(22L, 11L));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.getOutput()).isEqualTo("done");

        ArgumentCaptor<ExecutionCommand> captor = ArgumentCaptor.forClass(ExecutionCommand.class);
        verify(subTaskExecutionService).executeCommand(captor.capture());
        assertThat(captor.getValue().getSubTaskId()).isEqualTo(22L);
        assertThat(captor.getValue().getAgentId()).isEqualTo(11L);
        assertThat(captor.getValue().getTrigger()).isEqualTo(LegacyExecutorAdapter.TRIGGER_AGENT_RUNTIME);
    }

    @Test
    @DisplayName("失败结果映射：executeCommand 返回失败 → FAILED（不抛异常）")
    void shouldMapFailureAgentResult() {
        when(subTaskExecutionService.executeCommand(anyCommand())).thenReturn(AgentResult.failure("boom", "error", "ApiKeyAgentExecutor"));

        AgentExecutionResult result = adapter.execute(ctx(22L, 11L));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("boom");
    }

    @Test
    @DisplayName("业务校验失败降级：BizException → FAILED 契约化返回，不向上抛")
    void shouldMapBusinessExceptionToFailed() {
        when(subTaskExecutionService.executeCommand(anyCommand()))
                .thenThrow(new BizException("子任务不存在: 22"));

        AgentExecutionResult result = adapter.execute(ctx(22L, 11L));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getOutput()).contains("子任务不存在");
    }

    @Test
    @DisplayName("入参缺失防线：subTaskId 为空 → FAILED 且不调旧链")
    void shouldRejectMissingSubTaskId() {
        AgentExecutionResult result = adapter.execute(ctx(null, 11L));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        verify(subTaskExecutionService, never()).executeCommand(anyCommand());
    }

    private AgentContext ctx(Long subTaskId, Long agentId) {
        return AgentContext.builder()
                .runId("run-1-1")
                .taskId(1L)
                .subTaskId(subTaskId)
                .turn(1)
                .step(0)
                .agentId(agentId)
                .build();
    }

    private static ExecutionCommand anyCommand() {
        return org.mockito.ArgumentMatchers.any(ExecutionCommand.class);
    }
}