package com.helloai.core.task.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SubTaskStateMachine 状态流转单元测试（V25 补充 DEAD_LETTER 死信态）。
 */
@DisplayName("SubTaskStateMachine")
class SubTaskStateMachineTest {

    // ══════════════════════════════════════════════════════════════
    //  V25 死信态：进入 DEAD_LETTER
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("V25: PENDING/ASSIGNED/IN_PROGRESS/BLOCKED/REWORK 均可转入 DEAD_LETTER")
    void shouldAllowTransitionIntoDeadLetterFromDispatchStates() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.ASSIGNED, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.BLOCKED, SubTaskStatus.DEAD_LETTER)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REWORK, SubTaskStatus.DEAD_LETTER)).isTrue();
    }

    @Test
    @DisplayName("V25: PAUSED/REVIEW/DONE/CANCELLED 不允许转入 DEAD_LETTER")
    void shouldRejectTransitionIntoDeadLetterFromOtherStates() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PAUSED, SubTaskStatus.DEAD_LETTER)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REVIEW, SubTaskStatus.DEAD_LETTER)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DONE, SubTaskStatus.DEAD_LETTER)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.CANCELLED, SubTaskStatus.DEAD_LETTER)).isFalse();
    }

    // ══════════════════════════════════════════════════════════════
    //  V25 死信态：离开 DEAD_LETTER（仅人工指派 / 人工放弃）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("V25: DEAD_LETTER 仅允许转向 ASSIGNED（人工指派）或 CANCELLED（人工放弃）")
    void shouldOnlyAllowManualExitsFromDeadLetter() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.ASSIGNED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.CANCELLED)).isTrue();

        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.PENDING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.IN_PROGRESS)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.DONE)).isFalse();
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

    @Test
    @DisplayName("既有流转不受 V25 影响（抽样回归）")
    void shouldKeepExistingTransitionsIntact() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING, SubTaskStatus.ASSIGNED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REVIEW, SubTaskStatus.DONE)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DONE, SubTaskStatus.PENDING)).isFalse();
    }
}
