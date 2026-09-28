package com.helloai.core.task.statemachine;

import com.helloai.common.constant.FinalReportStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FinalReportStateMachine 单元测试（§12.5 状态机收口）。
 *
 * <p>覆盖：合法迁移全表、非法迁移拒绝、null 状态拒绝、以及
 * {@code assertTransit} 的快速失败语义。</p>
 */
@DisplayName("FinalReportStateMachine 最终报告状态迁移表")
class FinalReportStateMachineTest {

    @Test
    @DisplayName("合法迁移：NONE/GENERATING/REVIEWING/DONE/FAILED 全表放行")
    void shouldAllowLegalTransitions() {
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.NONE, FinalReportStatus.GENERATING))
                .isTrue();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.GENERATING, FinalReportStatus.REVIEWING))
                .isTrue();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.GENERATING, FinalReportStatus.DONE))
                .isTrue();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.GENERATING, FinalReportStatus.FAILED))
                .isTrue();
        // 审查驳回返工：报告正文被新一版覆盖，REVIEWING -> GENERATING 是 3A 闭环正常路径
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.REVIEWING, FinalReportStatus.GENERATING))
                .isTrue();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.REVIEWING, FinalReportStatus.DONE))
                .isTrue();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.DONE, FinalReportStatus.GENERATING))
                .isTrue();
        // 幂等：回滚上一版 / 兄弟链路重复收敛都需要 DONE -> DONE
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.DONE, FinalReportStatus.DONE))
                .isTrue();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.FAILED, FinalReportStatus.GENERATING))
                .isTrue();
        // §12.5 #1：markFailed 不清 prev 槽，“生成失败(FAILED) 且 prev 非空”可达；
        // 此时点「恢复上一版」把 prev 换回当前槽并置 DONE 是合法收敛，不得抛断言
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.FAILED, FinalReportStatus.DONE))
                .isTrue();
    }

    @Test
    @DisplayName("非法迁移：跳过必经态或倒退一律拒绝")
    void shouldRejectIllegalTransitions() {
        // NONE 没生成过，不可能直接进入 REVIEWING / DONE / FAILED
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.NONE, FinalReportStatus.REVIEWING))
                .isFalse();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.NONE, FinalReportStatus.DONE))
                .isFalse();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.NONE, FinalReportStatus.FAILED))
                .isFalse();
        // REVIEWING 是"已生成待审查"，不允许直接判失败（失败只发生在生成阶段）
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.REVIEWING, FinalReportStatus.FAILED))
                .isFalse();
        // FAILED 只能重试生成（->GENERATING）或恢复上一版（->DONE，见合法用例），不能直接跳到 REVIEWING
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.FAILED, FinalReportStatus.REVIEWING))
                .isFalse();
    }

    @Test
    @DisplayName("null 状态一律拒绝（状态缺失不允许迁移）")
    void shouldRejectNullStates() {
        assertThat(FinalReportStateMachine.canTransit(null, FinalReportStatus.GENERATING)).isFalse();
        assertThat(FinalReportStateMachine.canTransit(FinalReportStatus.NONE, null)).isFalse();
        assertThat(FinalReportStateMachine.canTransit(null, null)).isFalse();
    }

    @Test
    @DisplayName("assertTransit：非法迁移快速失败并携带允许集合，便于定位调用方")
    void shouldThrowOnIllegalTransition() {
        assertThatThrownBy(() ->
                FinalReportStateMachine.assertTransit(FinalReportStatus.NONE, FinalReportStatus.REVIEWING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NONE")
                .hasMessageContaining("REVIEWING");
    }

    @Test
    @DisplayName("assertTransit：合法迁移静默通过")
    void shouldPassOnLegalTransition() {
        FinalReportStateMachine.assertTransit(FinalReportStatus.GENERATING, FinalReportStatus.REVIEWING);
        FinalReportStateMachine.assertTransit(FinalReportStatus.REVIEWING, FinalReportStatus.DONE);
    }
}
