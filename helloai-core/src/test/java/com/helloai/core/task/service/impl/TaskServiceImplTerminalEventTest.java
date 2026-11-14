package com.helloai.core.task.service.impl;

import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.event.TaskTerminalStateEvent;
import com.helloai.core.task.entity.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@link TaskServiceImpl#updateStatus} 的任务终态事件发布（`SL-2`，2026-11-12）。
 *
 * <p><b>为什么只测 {@code DONE} 一个终态</b>：{@code CANCELLED} 与 {@code DONE} 在
 * 同一发布条件内、走同一条发布语句；`CANCELLED` 另有级联取消子任务的分支（需 mock 链式
 * wrapper），与「是否发布终态事件」无关，测它只会引入无关脆弱性。删除出口
 * （{@code deleteTaskCascade}）的发布由 SL-6 真机覆盖——该路径需 spy 十余个 mapper，
 * 单测成本远高于收益。</p>
 */
@DisplayName("TaskServiceImpl 任务终态事件")
class TaskServiceImplTerminalEventTest {

    private static final long TASK_ID = 9L;

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private TaskServiceImpl serviceWith(Task row) {
        TaskServiceImpl impl = spy(new TaskServiceImpl(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, publisher));
        doReturn(row).when(impl).getById(any());
        doReturn(true).when(impl).updateById(any());
        return impl;
    }

    @Test
    @DisplayName("updateStatus(DONE) ⇒ 发布终态事件（trigger=status_changed）")
    void shouldPublishWhenTaskDone() {
        Task row = new Task();
        row.setId(TASK_ID);
        row.setStatus(TaskStatus.IN_PROGRESS);

        serviceWith(row).updateStatus(TASK_ID, TaskStatus.DONE);

        ArgumentCaptor<TaskTerminalStateEvent> captor =
                ArgumentCaptor.forClass(TaskTerminalStateEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo(TASK_ID);
        assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(captor.getValue().getTrigger()).isEqualTo("status_changed");
    }

    @Test
    @DisplayName("updateStatus(IN_PROGRESS) ⇒ 非终态不发布（重发布/启动链路不受影响）")
    void shouldNotPublishForNonTerminalStatus() {
        Task row = new Task();
        row.setId(TASK_ID);
        row.setStatus(TaskStatus.PENDING);

        serviceWith(row).updateStatus(TASK_ID, TaskStatus.IN_PROGRESS);

        verify(publisher, never()).publishEvent(any());
    }
}
