package com.helloai.core.task.service.impl;

import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-006 外部执行轨迹对齐：状态退出 → 外部 attempt 终态映射。
 *
 * <p><b>为什么单独测这张表</b>：映射错了会静默污染审计与成本画像（例如把「人工取消」
 * 记成 SUCCESS，或把「提交核验」记成 TIMEOUT），而这类错误不会让任何主链路报错。
 * 纯函数直测是唯一的低成本防线。</p>
 */
class SubTaskStateMachineExternalExitTest {

    @Test
    @DisplayName("交付类退出（REVIEW / DONE）→ SUCCESS")
    void deliveredStatesMapToSuccess() {
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.REVIEW))
                .isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.DONE))
                .isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("平台收回类退出（BLOCKED / PENDING）→ TIMEOUT")
    void reclaimedStatesMapToTimeout() {
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.BLOCKED))
                .isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.PENDING))
                .isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    @DisplayName("终止类退出（CANCELLED / DEAD_LETTER）→ FAILED")
    void terminatedStatesMapToFailed() {
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.CANCELLED))
                .isEqualTo(ExecutionStatus.FAILED);
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.DEAD_LETTER))
                .isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("执行中 / 可续 / 未开工 / null → 不收口（attempt 保持 open）")
    void nonExitStatesDoNotClose() {
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.IN_PROGRESS)).isNull();
        // 暂停是可原地续跑的中间态：收口会把一次执行拆成两段，续跑后没有对应的 open 行
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.PAUSED)).isNull();
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.ASSIGNED)).isNull();
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.REWORK)).isNull();
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(SubTaskStatus.PENDING_PLAN_REVIEW)).isNull();
        assertThat(SubTaskStateMachineServiceImpl.externalExitStatus(null)).isNull();
    }
}
