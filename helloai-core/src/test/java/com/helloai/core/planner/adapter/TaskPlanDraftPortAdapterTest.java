package com.helloai.core.planner.adapter;

import com.helloai.core.planner.mapper.PlanDraftMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * `TaskPlanDraftPortAdapter` 单测（`AD-5b` 收口项 P4）。
 *
 * <p>拆解超时回收靠本端口判断「本轮是否真的产出了草案」；它必须看**有效轮次**
 * （`findMaxActiveRound`），不能看含软删的最大轮次——否则拒绝/确认后的全软删会被误判成
 * 「还有草案」，任务被错误推到 `AWAITING_PLAN_CONFIRM`。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskPlanDraftPortAdapter")
class TaskPlanDraftPortAdapterTest {

    private static final Long TASK_ID = 9001L;

    @Mock
    private PlanDraftMapper planDraftMapper;

    @Test
    @DisplayName("有有效轮次 ⇒ true")
    void shouldBeTrueWhenActiveRoundExists() {
        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(2);
        assertThat(new TaskPlanDraftPortAdapter(planDraftMapper).hasActiveDrafts(TASK_ID)).isTrue();
    }

    @Test
    @DisplayName("只有已软删历史轮次（active=0）⇒ false")
    void shouldBeFalseWhenOnlyDiscardedRounds() {
        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(0);
        assertThat(new TaskPlanDraftPortAdapter(planDraftMapper).hasActiveDrafts(TASK_ID)).isFalse();
    }

    @Test
    @DisplayName("taskId 为 null ⇒ false，不查库")
    void shouldBeFalseWhenTaskIdNull() {
        assertThat(new TaskPlanDraftPortAdapter(planDraftMapper).hasActiveDrafts(null)).isFalse();
        verifyNoInteractions(planDraftMapper);
    }
}