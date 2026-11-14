package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.event.TaskTerminalStateEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SandboxTaskTerminalListener} 单测（`SL-2`，2026-11-12）。
 *
 * <p><b>测的是什么</b>：事件到回收的接线与失败语义（吞异常、null 防御），而不是
 * {@code releaseByTask} 的槽级行为（后者在 {@code SandboxWorkspaceRegistryTest} /
 * {@code SandboxSlotPoolTest} 覆盖）。{@code @Async} / {@code AFTER_COMMIT} 的触发时机属
 * Spring 容器语义，由 B 级集成与 SL-6 真机覆盖，单测直接调方法。</p>
 */
@DisplayName("SandboxTaskTerminalListener")
class SandboxTaskTerminalListenerTest {

    private final SandboxExecutionService sandboxExecutionService = mock(SandboxExecutionService.class);

    private final SandboxTaskTerminalListener listener =
            new SandboxTaskTerminalListener(sandboxExecutionService);

    @Test
    @DisplayName("任务终态事件 ⇒ 按 taskId 调 releaseByTask")
    void shouldReleaseByTask() {
        listener.onTaskTerminal(new TaskTerminalStateEvent(7L, TaskStatus.DONE, "closed"));

        verify(sandboxExecutionService).releaseByTask(7L);
    }

    @Test
    @DisplayName("taskId 为空 ⇒ 不触发回收")
    void shouldIgnoreNullTaskId() {
        listener.onTaskTerminal(new TaskTerminalStateEvent(null, TaskStatus.CANCELLED, "status_changed"));

        verify(sandboxExecutionService, never()).releaseByTask(anyLong());
    }

    @Test
    @DisplayName("事件为 null ⇒ 静默返回")
    void shouldIgnoreNullEvent() {
        listener.onTaskTerminal(null);

        verify(sandboxExecutionService, never()).releaseByTask(anyLong());
    }

    @Test
    @DisplayName("回收抛异常 ⇒ 吞掉不下抛（交给空闲 TTL 兜底）")
    void shouldSwallowReleaseFailure() {
        doThrow(new IllegalStateException("sdk down")).when(sandboxExecutionService).releaseByTask(7L);

        assertThatCode(() -> listener.onTaskTerminal(
                new TaskTerminalStateEvent(7L, TaskStatus.DONE, "closed")))
                .doesNotThrowAnyException();
    }
}
