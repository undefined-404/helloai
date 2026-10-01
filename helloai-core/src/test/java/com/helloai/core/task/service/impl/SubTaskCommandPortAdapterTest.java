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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("start: 原子命令零判定，直接委派 SubTaskService.start（合法性由状态机 + 乐观锁兜底）")
    void shouldDelegateStartWithoutJudgement() {
        adapter.start(22L);

        verify(subTaskService).start(22L);
        // 零判定：不读库、不做状态白名单裁决（判定/协议码留在消费方协议适配层）
        verify(subTaskService, never()).getById(any());
    }

    @Test
    @DisplayName("claimAtomic: 委派条件更新并原样回传结果（true）")
    void shouldDelegateClaimAtomicTrue() {
        when(subTaskService.claimAtomic(22L, 7L)).thenReturn(true);

        assertThat(adapter.claimAtomic(22L, 7L)).isTrue();
    }

    @Test
    @DisplayName("claimAtomic: 委派条件更新并原样回传结果（false = 被抢走或状态已变）")
    void shouldDelegateClaimAtomicFalse() {
        when(subTaskService.claimAtomic(22L, 7L)).thenReturn(false);

        assertThat(adapter.claimAtomic(22L, 7L)).isFalse();
    }

    @Test
    @DisplayName("block: 委派 SubTaskService.block（阻塞写入与 PLANNER 通知整体在提供方）")
    void shouldDelegateBlock() {
        adapter.block(22L, "缺少上游产出", 7L);

        verify(subTaskService).block(22L, "缺少上游产出", 7L);
    }
}
