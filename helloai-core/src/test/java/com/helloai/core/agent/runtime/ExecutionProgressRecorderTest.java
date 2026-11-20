package com.helloai.core.agent.runtime;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ExecutionProgressRecorder} 单测（`MA-10` 改动 3）。
 *
 * <p>核心判据只有两条：<b>① 空 subTaskId 不写库</b>（业务面循环没有子任务，不能拿 null 去查表）；
 * <b>② 审批等待期间仍然刷新</b>——后者是本节最容易写错的点：跳过不写会让 {@code last_progress_at}
 * 冻在等待开始时刻，拖过阈值照样被判超时，「豁免」形同虚设。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExecutionProgressRecorder（MA-10 改动 3）")
class ExecutionProgressRecorderTest {

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;

    @Mock
    private SubTaskQueryPort subTaskQueryPort;

    @InjectMocks
    private ExecutionProgressRecorder recorder;

    @Test
    @DisplayName("touch：正常写入 last_progress_at")
    void touchWritesProgress() {
        recorder.touch(123L);

        verify(agentExecutionRecordService).touchProgress(eq(123L), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("★ touch：subTaskId 为 null ⇒ 不写库（业务面循环无子任务）")
    void touchIgnoresNullSubTaskId() {
        recorder.touch(null);

        verify(agentExecutionRecordService, never()).touchProgress(any(), any());
    }

    @Test
    @DisplayName("touch：写库抛异常被吞掉（观测旁路不得伤主链）")
    void touchSwallowsException() {
        // touchProgress 返回 void ⇒ 必须用 doThrow/doAnswer 形态，不能用 when(...).thenThrow
        doThrow(new RuntimeException("db down"))
                .when(agentExecutionRecordService).touchProgress(anyLong(), any(OffsetDateTime.class));

        // 不抛即通过；异常不得穿透到循环回调（否则一次 DB 抖动会打断整个 turn）
        recorder.touch(123L);
    }

    @Test
    @DisplayName("★ 审批等待中：仍然刷新进展 + 返回 true（豁免必须持续续命，不能跳过不写）")
    void approvalWaitStillRefreshesProgress() {
        when(subTaskQueryPort.findById(123L)).thenReturn(SubTaskSnapshot.builder()
                .id(123L)
                .context(Map.of(SubTaskContextKeys.PENDING_APPROVAL, Map.of("status", "PENDING")))
                .build());

        boolean awaiting = recorder.touchWithApprovalAwareness(123L);

        assertThat(awaiting).isTrue();
        // 关键：即使「没在推进」，也必须刷新——否则 last_progress_at 冻住，拖过阈值仍被判死
        verify(agentExecutionRecordService).touchProgress(eq(123L), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("非审批等待：照常刷新 + 返回 false")
    void normalProgressReportsNotAwaiting() {
        when(subTaskQueryPort.findById(123L)).thenReturn(SubTaskSnapshot.builder()
                .id(123L)
                .context(Map.of())
                .build());

        boolean awaiting = recorder.touchWithApprovalAwareness(123L);

        assertThat(awaiting).isFalse();
        verify(agentExecutionRecordService).touchProgress(eq(123L), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("context 为 null：按未等待处理，仍刷新进展")
    void nullContextTreatedAsNotAwaiting() {
        when(subTaskQueryPort.findById(123L))
                .thenReturn(SubTaskSnapshot.builder().id(123L).build());

        boolean awaiting = recorder.touchWithApprovalAwareness(123L);

        assertThat(awaiting).isFalse();
        verify(agentExecutionRecordService).touchProgress(eq(123L), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("★ 读 context 失败 ⇒ 按未等待（不给凭空豁免，那会永不收敛）")
    void readFailureTreatedAsNotAwaiting() {
        when(subTaskQueryPort.findById(123L)).thenThrow(new RuntimeException("task domain down"));

        boolean awaiting = recorder.touchWithApprovalAwareness(123L);

        assertThat(awaiting).isFalse();
        // 进展本身仍写入（touch 先执行且有独立 try-catch）
        verify(agentExecutionRecordService).touchProgress(eq(123L), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("snapshot 为 null：按未等待处理")
    void nullSnapshotTreatedAsNotAwaiting() {
        when(subTaskQueryPort.findById(123L)).thenReturn(null);

        boolean awaiting = recorder.touchWithApprovalAwareness(123L);

        assertThat(awaiting).isFalse();
    }

    @Test
    @DisplayName("touchWithApprovalAwareness：subTaskId 为 null ⇒ false 且不查库")
    void approvalAwarenessIgnoresNullSubTaskId() {
        assertThat(recorder.touchWithApprovalAwareness(null)).isFalse();

        verify(agentExecutionRecordService, never()).touchProgress(any(), any());
        verify(subTaskQueryPort, never()).findById(any());
    }
}
