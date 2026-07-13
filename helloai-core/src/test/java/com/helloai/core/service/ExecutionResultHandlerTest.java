package com.helloai.core.service;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionResultHandler")
class ExecutionResultHandlerTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @InjectMocks
    private ExecutionResultHandler executionResultHandler;

    @Test
    @DisplayName("成功结果回写 context、推进 REVIEW 并记录时间线")
    void shouldHandleSuccess() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        AgentResult result = AgentResult.success("done", "stop", "ApiKeyAgentExecutor", 12);

        executionResultHandler.handleSuccess(22L, 11L, result);

        ArgumentCaptor<SubTask> subTaskCaptor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(subTaskCaptor.capture());
        Map<String, Object> context = subTaskCaptor.getValue().getContext();
        assertThat(context).containsKey("lastExecution");
        @SuppressWarnings("unchecked")
        Map<String, Object> lastExecution = (Map<String, Object>) context.get("lastExecution");
        assertThat(lastExecution)
                .containsEntry("agentId", 11L)
                .containsEntry("success", true)
                .containsEntry("executor", "ApiKeyAgentExecutor")
                .containsEntry("finishReason", "stop")
                .containsEntry("tokens", 12)
                .containsEntry("output", "done");

        verify(subTaskService).submit(22L);
        verify(taskTimelineService).recordEvent(
                33L, 22L, "sub_task_execute_submit", AgentRole.EXECUTOR, 11L,
                Map.of("success", true, "executor", "ApiKeyAgentExecutor", "tokens", 12));
    }

    @Test
    @DisplayName("失败结果回写 context、推进 BLOCKED 并记录失败时间线")
    void shouldHandleFailure() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        executionResultHandler.handleFailure(22L, 11L, new RuntimeException("boom"));

        ArgumentCaptor<SubTask> subTaskCaptor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService).updateById(subTaskCaptor.capture());
        Map<String, Object> context = subTaskCaptor.getValue().getContext();
        assertThat(context).containsKey("lastExecution");
        @SuppressWarnings("unchecked")
        Map<String, Object> lastExecution = (Map<String, Object>) context.get("lastExecution");
        assertThat(lastExecution)
                .containsEntry("agentId", 11L)
                .containsEntry("success", false)
                .containsEntry("error", "boom");

        verify(subTaskService).block(22L);
        verify(taskTimelineService).recordEvent(
                33L, 22L, "sub_task_execute_failed", AgentRole.EXECUTOR, 11L,
                Map.of("error", "boom"));
    }

    @Test
    @DisplayName("P2-2: 补偿任务将 subTask 推进到 BLOCKED 后，handleSuccess 不应复活到 REVIEW")
    void shouldNotReviveSubTaskWhenStatusIsBlocked() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setStatus(SubTaskStatus.BLOCKED);  // 已被补偿任务推进
        when(subTaskService.getById(22L)).thenReturn(subTask);

        AgentResult result = AgentResult.success("done", "stop", "ApiKeyAgentExecutor", 12);

        executionResultHandler.handleSuccess(22L, 11L, result);

        // 不应推进到 REVIEW
        verify(subTaskService, never()).submit(22L);
        // 不应覆写 context
        verify(subTaskService, never()).updateById(org.mockito.ArgumentMatchers.any(SubTask.class));
        // 应记录 "结果被丢弃" 事件
        verify(taskTimelineService).recordEvent(
                org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq("sub_task_execute_result_discarded"),
                org.mockito.ArgumentMatchers.eq(AgentRole.EXECUTOR),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("P2-3: handleFailure 对非 IN_PROGRESS 状态的子任务不应调用 block")
    void shouldNotBlockWhenStatusIsNotInProgress() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setStatus(SubTaskStatus.BLOCKED);  // 已经是 BLOCKED
        when(subTaskService.getById(22L)).thenReturn(subTask);

        executionResultHandler.handleFailure(22L, 11L, new RuntimeException("boom"));

        // 不应再次 block
        verify(subTaskService, never()).block(22L);
        // 但应该记录失败事件
        verify(taskTimelineService).recordEvent(
                33L, 22L, "sub_task_execute_failed", AgentRole.EXECUTOR, 11L,
                Map.of("error", "boom"));
    }
}
