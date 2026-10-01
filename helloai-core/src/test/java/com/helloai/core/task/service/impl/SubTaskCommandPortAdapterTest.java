package com.helloai.core.task.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@code SubTaskCommandPortAdapter} 单测。
 *
 * <p>本测试由原 {@code agent.execution.SubTaskExecutionServiceTest} 平移而来（2026-10-01，W3）：
 * 「判定 + 写」整体迁回 task 域后，用例断言与覆盖点<b>逐条不变</b>，只是被测类换到提供方一侧。
 * 覆盖 {@code startIfNeeded} 的幂等与边界，以及 {@code unlinkByAssignedAgent} 的薄委托。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskCommandPortAdapter")
class SubTaskCommandPortAdapterTest {

    @Mock
    private SubTaskService subTaskService;

    @InjectMocks
    private SubTaskCommandPortAdapter adapter;

    @Test
    @DisplayName("startIfNeeded: IN_PROGRESS 幂等跳过，不触发状态推进")
    void shouldSkipWhenInProgress() {
        adapter.startIfNeeded(1L, SubTaskStatus.IN_PROGRESS);

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("startIfNeeded: ASSIGNED → 状态推进（IN_PROGRESS）")
    void shouldStartWhenAssigned() {
        adapter.startIfNeeded(1L, SubTaskStatus.ASSIGNED);

        verify(subTaskService).start(1L);
    }

    @Test
    @DisplayName("startIfNeeded: REWORK → 状态推进（IN_PROGRESS）")
    void shouldStartWhenRework() {
        adapter.startIfNeeded(1L, SubTaskStatus.REWORK);

        verify(subTaskService).start(1L);
    }

    @Test
    @DisplayName("startIfNeeded: PAUSED → 状态推进（IN_PROGRESS）")
    void shouldStartWhenPaused() {
        adapter.startIfNeeded(1L, SubTaskStatus.PAUSED);

        verify(subTaskService).start(1L);
    }

    @Test
    @DisplayName("startIfNeeded: DONE 不允许执行 → BizException")
    void shouldRejectWhenDone() {
        assertThatThrownBy(() -> adapter.startIfNeeded(1L, SubTaskStatus.DONE))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许执行");

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("startIfNeeded: REVIEW 不允许执行 → BizException")
    void shouldRejectWhenReview() {
        assertThatThrownBy(() -> adapter.startIfNeeded(1L, SubTaskStatus.REVIEW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许执行");

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("startIfNeeded: null 状态不允许执行 → BizException")
    void shouldRejectWhenNullStatus() {
        assertThatThrownBy(() -> adapter.startIfNeeded(1L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许执行");

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("unlinkByAssignedAgent: 薄委托到 SubTaskService（级联删除解绑语义不变）")
    void shouldUnlinkByAssignedAgent() {
        adapter.unlinkByAssignedAgent(9L);

        verify(subTaskService).unlinkByAssignedAgent(9L);
    }
}
