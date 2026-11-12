package com.helloai.core.planner.service.impl;

import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.task.port.SubTaskDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * `PlanDraftRoundService#writeDrafts` 轮次分配单测（`AD-5b` 收口项 P4）。
 *
 * <p><b>为什么需要它</b>：`findMaxRound` 必须统计**含软删**的历史行。拒绝/确认都会把旧轮
 * `deleted=1`，若只数有效行，重拆会再次拿到 round=1，历史轮次无法区分；而读侧/存在性判断
 * 仍必须只看有效行（否则全软删会被误判为「还有草案」）。本类把两种口径钉在各自调用方。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanDraftRoundService.writeDrafts")
class PlanDraftRoundServiceImplTest {

    private static final Long TASK_ID = 9001L;
    private static final String BATCH_KEY = "batch-1";

    @Mock
    private PlanDraftMapper planDraftMapper;

    @Test
    @DisplayName("上一轮已软删（max round 含软删=1）⇒ 本轮写到 round=2")
    void shouldUseNextRoundIncludingDiscardedRows() {
        when(planDraftMapper.countByBatchKey(BATCH_KEY)).thenReturn(0L);
        when(planDraftMapper.findMaxRound(TASK_ID)).thenReturn(1);
        PlanDraftRoundServiceImpl service = new PlanDraftRoundServiceImpl(planDraftMapper);

        int written = service.writeDrafts(TASK_ID, BATCH_KEY,
                OffsetDateTime.parse("2026-11-10T10:00:00+08:00"),
                List.of(draft("202611100001")));

        assertThat(written).isEqualTo(1);
        ArgumentCaptor<PlanDraft> captor = ArgumentCaptor.forClass(PlanDraft.class);
        verify(planDraftMapper).insert(captor.capture());
        assertThat(captor.getValue().getRound()).isEqualTo(2);
        assertThat(captor.getValue().getCode()).isEqualTo("202611100001");
    }

    @Test
    @DisplayName("同一 batchKey 已写过 ⇒ 整批跳过，不再分配轮次/插入")
    void shouldSkipWhenBatchAlreadyWritten() {
        when(planDraftMapper.countByBatchKey(BATCH_KEY)).thenReturn(1L);
        PlanDraftRoundServiceImpl service = new PlanDraftRoundServiceImpl(planDraftMapper);

        assertThat(service.writeDrafts(TASK_ID, BATCH_KEY, OffsetDateTime.now(),
                List.of(draft("202611100001")))).isZero();

        verify(planDraftMapper, never()).findMaxRound(anyLong());
        verify(planDraftMapper, never()).insert(any(PlanDraft.class));
    }

    @Test
    @DisplayName("空批次 ⇒ 0，不查库")
    void shouldReturnZeroOnEmptyDrafts() {
        PlanDraftRoundServiceImpl service = new PlanDraftRoundServiceImpl(planDraftMapper);

        assertThat(service.writeDrafts(TASK_ID, BATCH_KEY, OffsetDateTime.now(), List.of())).isZero();

        verifyNoInteractions(planDraftMapper);
    }

    private static SubTaskDraft draft(String code) {
        return new SubTaskDraft(TASK_ID, "子任务", "内容", "交付物", "通过",
                "MEDIUM", 0, List.of(), null, SubTaskStatus.PENDING, Map.of(), List.of(),
                AcceptanceKind.AUTOMATED, 1, code);
    }
}