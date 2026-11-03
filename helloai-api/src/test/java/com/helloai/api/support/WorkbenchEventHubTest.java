package com.helloai.api.support;

import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchEventHub} 单测（PA-6）：事件广播按任务 owner 过滤。
 */
class WorkbenchEventHubTest {

    @Test
    void onSubTaskCompleted_broadcastsOnlyToTaskOwner() throws Exception {
        TaskService taskService = mock(TaskService.class);
        WorkbenchEventHub hub = new WorkbenchEventHub(taskService);
        Task task = new Task();
        task.setOwnerId(1L);
        when(taskService.getById(20L)).thenReturn(task);
        SseEmitter owner = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        addEmitter(hub, owner, 1L);
        addEmitter(hub, other, 2L);

        hub.onSubTaskCompleted(new SubTaskCompletedEvent(10L, 20L));

        verify(owner, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void onTaskAutoCompleted_legacyOwnerNull_broadcastsToAll() throws Exception {
        TaskService taskService = mock(TaskService.class);
        WorkbenchEventHub hub = new WorkbenchEventHub(taskService);
        when(taskService.getById(20L)).thenReturn(new Task());
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        addEmitter(hub, first, 1L);
        addEmitter(hub, second, 2L);

        hub.onTaskAutoCompleted(new TaskAutoCompletedEvent(20L));

        verify(first, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void taskNotFound_skipsBroadcast() throws Exception {
        TaskService taskService = mock(TaskService.class);
        WorkbenchEventHub hub = new WorkbenchEventHub(taskService);
        when(taskService.getById(99L)).thenReturn(null);
        SseEmitter emitter = mock(SseEmitter.class);
        addEmitter(hub, emitter, 1L);

        hub.onSubTaskCompleted(new SubTaskCompletedEvent(1L, 99L));

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void noEmitters_noBroadcastAttempt() {
        WorkbenchEventHub hub = new WorkbenchEventHub(mock(TaskService.class));
        hub.onSubTaskCompleted(new SubTaskCompletedEvent(1L, 2L));
    }

    @SuppressWarnings("unchecked")
    private static void addEmitter(WorkbenchEventHub hub, SseEmitter emitter, Long userId) throws Exception {
        Field field = WorkbenchEventHub.class.getDeclaredField("emitters");
        field.setAccessible(true);
        ((List<WorkbenchEventHub.Emitter>) field.get(hub))
                .add(new WorkbenchEventHub.Emitter(emitter, userId));
    }
}