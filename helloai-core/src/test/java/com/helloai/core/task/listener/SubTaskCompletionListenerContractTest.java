package com.helloai.core.task.listener;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.system.entity.Attachment;
import com.helloai.core.system.service.AttachmentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubTaskCompletionListener 契约产出回流（Phase 2）测试：
 * isContract 检测 / 物化附件优先 / output 回退 / 无产出跳过 /
 * 非契约零动作 / 失败 best-effort 不阻断收尾链。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskCompletionListener contract backfill")
class SubTaskCompletionListenerContractTest {

    private static final Long TASK_ID = 100L;
    private static final Long SUB_TASK_ID = 11L;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private SubTaskDispatchService subTaskDispatchService;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @Mock
    private AttachmentService attachmentService;

    @InjectMocks
    private SubTaskCompletionListener listener;

    private SubTask contractSubTask() {
        SubTask subTask = new SubTask();
        subTask.setId(SUB_TASK_ID);
        subTask.setTaskId(TASK_ID);
        subTask.setTitle("契约定义");
        subTask.setIsContract(1);
        return subTask;
    }

    /** 完成事件的公共 stub：unlockDownstream/tryCloseTask 在无 PENDING 子任务时静默返回。 */
    private void stubCompletionChain() {
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of());
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);
    }

    @Test
    @DisplayName("契约子任务 + 物化附件产出 → updateContract + success timeline")
    void shouldBackfillContractFromMaterializedAttachment() {
        SubTask subTask = contractSubTask();
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        stubCompletionChain();

        Attachment attachment = new Attachment();
        attachment.setId(5L);
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of(attachment));
        when(attachmentService.isContentLoadable(attachment)).thenReturn(true);
        when(attachmentService.loadContent(5L))
                .thenReturn("接口签名：POST /api/orders".getBytes(StandardCharsets.UTF_8));

        listener.onSubTaskCompleted(new SubTaskCompletedEvent(SUB_TASK_ID, TASK_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(taskRunningSpecService).updateContract(eq(TASK_ID), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("subTaskId", SUB_TASK_ID)
                .containsEntry("title", "契约定义")
                .containsEntry("content", "接口签名：POST /api/orders")
                .containsKey("backfilledAt");

        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_contract_backfilled"),
                eq(AgentRole.SYSTEM), isNull(), any());
    }

    @Test
    @DisplayName("无物化附件 → 回退 context.lastExecution.output")
    void shouldFallbackToLastExecutionOutput() {
        SubTask subTask = contractSubTask();
        subTask.setContext(Map.of("lastExecution", Map.of("output", "错误码表：400/401/500")));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        stubCompletionChain();
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of());

        listener.onSubTaskCompleted(new SubTaskCompletedEvent(SUB_TASK_ID, TASK_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(taskRunningSpecService).updateContract(eq(TASK_ID), captor.capture());
        assertThat(captor.getValue()).containsEntry("content", "错误码表：400/401/500");
    }

    @Test
    @DisplayName("契约子任务无产出 → skipped timeline，不写契约")
    void shouldSkipWhenNoOutput() {
        SubTask subTask = contractSubTask();
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        stubCompletionChain();
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of());

        listener.onSubTaskCompleted(new SubTaskCompletedEvent(SUB_TASK_ID, TASK_ID));

        verify(taskRunningSpecService, never()).updateContract(anyLong(), any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_contract_backfilled"),
                eq(AgentRole.SYSTEM), isNull(),
                eq(Map.of("status", "skipped", "reason", "no_output")));
    }

    @Test
    @DisplayName("普通子任务（isContract=0/null）→ 零动作：不更新契约也不写 timeline")
    void shouldIgnoreNonContractSubTask() {
        SubTask subTask = contractSubTask();
        subTask.setIsContract(0);
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        stubCompletionChain();

        listener.onSubTaskCompleted(new SubTaskCompletedEvent(SUB_TASK_ID, TASK_ID));

        verify(taskRunningSpecService, never()).updateContract(anyLong(), any());
        verify(attachmentService, never()).listActive(anyLong());
        verify(taskTimelineService, never()).recordEvent(
                anyLong(), anyLong(), eq("sub_task_contract_backfilled"), any(), any(), any());
    }

    @Test
    @DisplayName("updateContract 失败 → failed timeline + best-effort 不抛出（解锁下游照常执行）")
    void shouldRecordFailedTimelineWhenUpdateContractThrows() {
        SubTask subTask = contractSubTask();
        subTask.setContext(Map.of("lastExecution", Map.of("output", "契约正文")));
        when(subTaskService.getById(SUB_TASK_ID)).thenReturn(subTask);
        stubCompletionChain();
        when(attachmentService.listActive(SUB_TASK_ID)).thenReturn(List.of());
        doThrow(new RuntimeException("db down"))
                .when(taskRunningSpecService).updateContract(eq(TASK_ID), any());

        // best-effort：不抛异常（解锁下游 / 收尾链不被阻断）
        listener.onSubTaskCompleted(new SubTaskCompletedEvent(SUB_TASK_ID, TASK_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(SUB_TASK_ID), eq("sub_task_contract_backfilled"),
                eq(AgentRole.SYSTEM), isNull(), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("status", "failed")
                .containsEntry("error", "db down");
    }
}
