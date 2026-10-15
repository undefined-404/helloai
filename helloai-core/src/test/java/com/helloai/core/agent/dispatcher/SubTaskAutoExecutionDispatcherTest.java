package com.helloai.core.agent.dispatcher;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.shared.event.SubTaskAssignedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.service.AgentService;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskAutoExecutionDispatcher")
class SubTaskAutoExecutionDispatcherTest {

    @Mock
    private AgentService agentService;

    @Mock
    private SubTaskQueryPort subTaskQueryPort;

    @Mock
    private ExecutionCommandService executionCommandService;

    @Mock
    private TaskTimelinePort taskTimelinePort;

    @InjectMocks
    private SubTaskAutoExecutionDispatcher dispatcher;

    @Test
    @DisplayName("API_KEY_LLM 在 ASSIGNED 后创建执行命令")
    void shouldCreateExecutionCommandWhenAssignedAgentIsApiKeyLlm() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        SubTaskSnapshot subTask = SubTaskSnapshot.builder()
                .id(22L)
                .taskId(33L)
                .build();

        when(agentService.getById(11L)).thenReturn(agent);
        when(subTaskQueryPort.findById(22L)).thenReturn(subTask);

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        verify(taskTimelinePort).recordEvent(
                33L, 22L, "sub_task_auto_execute_dispatch", AgentRole.SYSTEM, 11L,
                java.util.Map.of("trigger", "assigned", "accessType", "API_KEY_LLM"));
        verify(executionCommandService).createAssignedCommand(22L, 11L, "assigned", List.of());
    }

    @Test
    @DisplayName("CLI_CLIENT 在 ASSIGNED 后跳过自动执行")
    void shouldSkipWhenAssignedAgentIsCliClient() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);

        when(agentService.getById(11L)).thenReturn(agent);

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        verify(subTaskQueryPort, never()).findById(22L);
        verify(taskTimelinePort, never()).recordEvent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        verify(executionCommandService, never()).createAssignedCommand(22L, 11L, "assigned", List.of());
    }

    @Test
    @DisplayName("HIGH-1: 派发失败 → 落 sub_task_auto_execute_dispatch_fail 时间线（原仅 log，操作面不可见）")
    void shouldRecordTimelineWhenDispatchFails() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        SubTaskSnapshot subTask = SubTaskSnapshot.builder()
                .id(22L)
                .taskId(33L)
                .build();

        when(agentService.getById(11L)).thenReturn(agent);
        when(subTaskQueryPort.findById(22L)).thenReturn(subTask);
        // 命令创建抛异常（模拟锁失败 / 临时 DB 错误 / hasPendingOrRunning）
        when(executionCommandService.createAssignedCommand(22L, 11L, "assigned", List.of()))
                .thenThrow(new IllegalStateException("hasPendingOrRunning"));

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        // 关键：失败必须留痕（否则子任务停在 ASSIGNED 且无 record，用户侧只见结果不见前因）
        verify(taskTimelinePort).recordEvent(
                org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq("sub_task_auto_execute_dispatch_fail"),
                org.mockito.ArgumentMatchers.eq(AgentRole.SYSTEM),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.argThat(m ->
                        "IllegalStateException".equals(m.get("reason"))
                                && "assigned_timeout_reassign".equals(m.get("recoveryHint"))));
    }

    @Test
    @DisplayName("HIGH-1: 时间线记录自身失败不得掩盖派发异常（best-effort 降级）")
    void shouldSwallowTimelineFailureOnDispatchFailure() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        SubTaskSnapshot subTask = SubTaskSnapshot.builder()
                .id(22L)
                .taskId(33L)
                .build();

        when(agentService.getById(11L)).thenReturn(agent);
        when(subTaskQueryPort.findById(22L)).thenReturn(subTask);
        // 时间线端口全抛（DB 抖动）—— recordEvent 返回 void，必须用 doThrow 形态打桩。
        // 首个「派单」事件即失败 ⇒ 派发本身（createAssignedCommand）不应被挡住，
        // 且失败事件的记录失败也应被内层 try/catch 吞掉（方法整体不得抛异常）。
        org.mockito.Mockito.doThrow(new RuntimeException("timeline down"))
                .when(taskTimelinePort).recordEvent(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatCode(
                        () -> dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L)))
                .doesNotThrowAnyException();
    }
}
