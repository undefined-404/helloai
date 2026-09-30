package com.helloai.core.agent.execution;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.service.impl.SubTaskExecutionServiceImpl;
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
 * SubTaskExecutionService 单测（G-002 单轨后仅 startIfNeeded）。
 *
 * <p>旧链 executeCommand / executeOnce 已随 Legacy 执行链退役（2026-09-30）：装配行为由
 * {@code AgentRuntimeContextAssembler} 承接、执行编排由 {@code LocalExecutionCommandConsumer}
 * 承接；本测试仅覆盖状态推进入口的幂等与边界。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskExecutionService.startIfNeeded")
class SubTaskExecutionServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @InjectMocks
    private SubTaskExecutionServiceImpl subTaskExecutionService;

    @Test
    @DisplayName("IN_PROGRESS 幂等跳过：不触发状态推进")
    void shouldSkipWhenInProgress() {
        subTaskExecutionService.startIfNeeded(1L, SubTaskStatus.IN_PROGRESS);

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("ASSIGNED → 状态推进（IN_PROGRESS）")
    void shouldStartWhenAssigned() {
        subTaskExecutionService.startIfNeeded(1L, SubTaskStatus.ASSIGNED);

        verify(subTaskService).start(1L);
    }

    @Test
    @DisplayName("REWORK → 状态推进（IN_PROGRESS）")
    void shouldStartWhenRework() {
        subTaskExecutionService.startIfNeeded(1L, SubTaskStatus.REWORK);

        verify(subTaskService).start(1L);
    }

    @Test
    @DisplayName("PAUSED → 状态推进（IN_PROGRESS）")
    void shouldStartWhenPaused() {
        subTaskExecutionService.startIfNeeded(1L, SubTaskStatus.PAUSED);

        verify(subTaskService).start(1L);
    }

    @Test
    @DisplayName("DONE 不允许执行 → BizException")
    void shouldRejectWhenDone() {
        assertThatThrownBy(() -> subTaskExecutionService.startIfNeeded(1L, SubTaskStatus.DONE))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许执行");

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("REVIEW 不允许执行 → BizException")
    void shouldRejectWhenReview() {
        assertThatThrownBy(() -> subTaskExecutionService.startIfNeeded(1L, SubTaskStatus.REVIEW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许执行");

        verify(subTaskService, never()).start(any());
    }

    @Test
    @DisplayName("null 状态不允许执行 → BizException")
    void shouldRejectWhenNullStatus() {
        assertThatThrownBy(() -> subTaskExecutionService.startIfNeeded(1L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态不允许执行");

        verify(subTaskService, never()).start(any());
    }
}