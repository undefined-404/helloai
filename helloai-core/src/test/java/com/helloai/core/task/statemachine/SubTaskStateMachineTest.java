package com.helloai.core.task.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SubTaskStateMachine 状态流转单元测试（补充 DEAD_LETTER 死信态；
 * 补充 ADR-008 的 AWAITING_DERIVED / RESTRUCTURING 派生态）。
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
    //  返工态出口（P0-2 附带修复：REWORK → BLOCKED）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REWORK → IN_PROGRESS / BLOCKED 合法（开工出口 + 返工途中可上报阻塞）")
    void shouldAllowReworkExits() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REWORK, SubTaskStatus.IN_PROGRESS)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REWORK, SubTaskStatus.BLOCKED)).isTrue();
        assertThatCode(() -> SubTaskStateMachine.validate(SubTaskStatus.REWORK, SubTaskStatus.BLOCKED))
                .doesNotThrowAnyException();
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
    //  租约回收态：IN_PROGRESS → PENDING
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

    // ══════════════════════════════════════════════════════════════
    //  派生态：AWAITING_DERIVED / RESTRUCTURING（ADR-008）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RESTRUCTURING 只接受人工接管触发态：BLOCKED / DEAD_LETTER / REWORK / PAUSED")
    void shouldOnlyEnterRestructuringFromTakeoverStates() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.BLOCKED, SubTaskStatus.RESTRUCTURING)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DEAD_LETTER, SubTaskStatus.RESTRUCTURING)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REWORK, SubTaskStatus.RESTRUCTURING)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PAUSED, SubTaskStatus.RESTRUCTURING)).isTrue();

        // 正常在途状态不得直接跳重构：重构的前提是「已判定卡住/返工失败」
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.PENDING, SubTaskStatus.RESTRUCTURING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.ASSIGNED, SubTaskStatus.RESTRUCTURING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.IN_PROGRESS, SubTaskStatus.RESTRUCTURING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.REVIEW, SubTaskStatus.RESTRUCTURING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.DONE, SubTaskStatus.RESTRUCTURING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.CANCELLED, SubTaskStatus.RESTRUCTURING)).isFalse();
    }

    @Test
    @DisplayName("AWAITING_DERIVED 只允许 DONE / RESTRUCTURING / CANCELLED，不可退回分发链")
    void shouldOnlyAllowDerivedExits() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.DONE)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.RESTRUCTURING)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.CANCELLED)).isTrue();

        // 工作已整体移交派生 Task，退回分发链会重复执行
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.PENDING)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.ASSIGNED)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.IN_PROGRESS)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.REVIEW)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.DEAD_LETTER)).isFalse();
    }

    @Test
    @DisplayName("RESTRUCTURING 出边：PENDING（放弃重构退回重派）/ AWAITING_DERIVED（重新派生）/ CANCELLED")
    void shouldOnlyAllowRestructuringExits() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.PENDING)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.AWAITING_DERIVED)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.CANCELLED)).isTrue();

        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.ASSIGNED)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.IN_PROGRESS)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.REVIEW)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.DONE)).isFalse();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.DEAD_LETTER)).isFalse();
    }

    @Test
    @DisplayName("派生态仍是可流转态：AWAITING_DERIVED / RESTRUCTURING 均不是终态")
    void shouldTreatDerivedStatesAsNonTerminal() {
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.DONE)).isTrue();
        assertThat(SubTaskStateMachine.canTransition(SubTaskStatus.RESTRUCTURING, SubTaskStatus.CANCELLED)).isTrue();
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
