package com.helloai.core.task.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SubTaskStateMachine 状态流转单元测试（补充 DEAD_LETTER 死信态；补充 PENDING_PLAN_REVIEW 草案态）。
 */
@DisplayName("SubTaskStateMachine")
class SubTaskStateMachineTest {

    // ══════════════════════════════════════════════════════════════
    //  死信态：进入 DEAD_LETTER
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PENDING/ASSIGNED/IN_PROGRESS/BLOCKED/REWORK/REVIEW 均可转入 DEAD_LETTER")
    void shouldAllowTransitionIntoDeadLetterFromDispatchStates() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.ASSIGNED, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.BLOCKED, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REWORK, SubTaskStatus.DEAD_LETTER)).isTrue();
        // 核验返工熔断（返工达上限自动核验停止）：REVIEW → DEAD_LETTER（与调度熔断对称）
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REVIEW, SubTaskStatus.DEAD_LETTER)).isTrue();
    }

    @Test
    @DisplayName("PAUSED/DONE/CANCELLED 不允许转入 DEAD_LETTER")
    void shouldRejectTransitionIntoDeadLetterFromOtherStates() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PAUSED, SubTaskStatus.DEAD_LETTER)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DONE, SubTaskStatus.DEAD_LETTER)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER)).isFalse();
    }

    // ══════════════════════════════════════════════════════════════
    //  死信态：离开 DEAD_LETTER（仅人工处置：指派 / 放弃 / 直接验收 / 驳回改派）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DEAD_LETTER 仅允许人工转出：ASSIGNED（指派）CANCELLED（放弃）DONE（直接验收）REWORK（驳回改派）")
    void shouldOnlyAllowManualExitsFromDeadLetter() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.ASSIGNED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.CANCELLED)).isTrue();
        // 人工介入面板从死信池打捞：直接通过 → DONE；驳回改派 → REWORK
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.DONE)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.REWORK)).isTrue();

        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.PENDING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.IN_PROGRESS)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.PAUSED)).isFalse();
    }

    @Test
    @DisplayName("非法流转 validate 抛 BizException")
    void shouldThrowOnIllegalTransition() {
        assertThatThrownBy(() -> SubTaskStateMachine.validate(SubTaskStatus.DONE, SubTaskStatus.DEAD_LETTER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态转换");
        assertThatThrownBy(() -> SubTaskStateMachine.validate(SubTaskStatus.DEAD_LETTER, SubTaskStatus.PENDING))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态转换");
    }

    // ══════════════════════════════════════════════════════════════
    //  规划草案态：PENDING_PLAN_REVIEW
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PENDING_PLAN_REVIEW 仅允许转向 PENDING（确认转正）或 CANCELLED（拒绝草案）")
    void shouldOnlyAllowConfirmOrRejectFromPlanReview() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.PENDING)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.CANCELLED)).isTrue();

        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.ASSIGNED)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.IN_PROGRESS)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.DONE)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.DEAD_LETTER)).isFalse();
    }

    @Test
    @DisplayName("任何状态都不允许转入 PENDING_PLAN_REVIEW（草案态只能由拆解落库产生）")
    void shouldRejectAnyTransitionIntoPlanReview() {
        for (SubTaskStatus from : SubTaskStatus.values()) {
            assertThat(SubTaskStateMachine.canTransition(from, SubTaskStatus.PENDING_PLAN_REVIEW))
                    .as("from=%s", from)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("草案态非法流转 validate 抛 BizException")
    void shouldThrowOnIllegalPlanReviewTransition() {
        assertThatThrownBy(() -> SubTaskStateMachine.validate(SubTaskStatus.PENDING_PLAN_REVIEW, SubTaskStatus.ASSIGNED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态转换");
        assertThatThrownBy(() -> SubTaskStateMachine.validate(SubTaskStatus.PENDING, SubTaskStatus.PENDING_PLAN_REVIEW))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态转换");
    }

    // ══════════════════════════════════════════════════════════════
    //  租约回收态：IN_PROGRESS → PENDING（Phase 0 A2.5）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IN_PROGRESS 允许转向 PENDING（仅租约过期回收路径）")
    void shouldAllowLeaseReclaimFromInProgress() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.PENDING)).isTrue();
    }

    @Test
    @DisplayName("非回收路径不得把 IN_PROGRESS 打回 PENDING：直接调用归还语义不受影响")
    void shouldKeepOtherInProgressTransitionsIntact() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.REVIEW)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.BLOCKED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.PAUSED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.CANCELLED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.DEAD_LETTER)).isTrue();
    }

    @Test
    @DisplayName("IN_PROGRESS → PENDING 回收转换 validate 通过")
    void shouldValidateLeaseReclaimTransition() {
        assertThatCode(() -> SubTaskStateMachine.validate(SubTaskStatus.IN_PROGRESS, SubTaskStatus.PENDING))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("既有流转不受影响（抽样回归）")
    void shouldKeepExistingTransitionsIntact() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING, SubTaskStatus.ASSIGNED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REVIEW, SubTaskStatus.DONE)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DONE, SubTaskStatus.PENDING)).isFalse();
    }
}
