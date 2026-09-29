package com.helloai.core.review.support;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * {@link FinalReportReviewFallbackWriter} 单元测试（§12.5.5 #4）。
 *
 * <p>聚焦「兜底落库委托契约」：两个 {@code REQUIRES_NEW} 方法必须把审计与收敛写回落到
 * {@code TaskTimelineService} / {@code TaskService}——写入器存在的意义就是让 AFTER_COMMIT
 * 发布线程上的兜底写入经独立事务确定提交（事务传播属容器语义，由 B 级 IT / 运行时保证，
 * 本单测只锁委托链路不被后续重构悄悄丢弃）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FinalReportReviewFallbackWriter 兜底落库委托（§12.5.5 #4）")
class FinalReportReviewFallbackWriterTest {

    private static final Long TASK_ID = 1L;
    private static final OffsetDateTime REPORT_TIME = OffsetDateTime.parse("2026-09-28T10:00:00+08:00");

    @Mock
    private TaskService taskService;
    @Mock
    private TaskTimelineService taskTimelineService;

    @InjectMocks
    private FinalReportReviewFallbackWriter writer;

    @Test
    @DisplayName("convergeToDone：委托 TaskService.convergeFinalReportToDone（状态+时间双条件 CAS 收敛）")
    void shouldDelegateConvergeToDone() {
        writer.convergeToDone(TASK_ID, REPORT_TIME);

        verify(taskService).convergeFinalReportToDone(TASK_ID, REPORT_TIME);
    }

    @Test
    @DisplayName("recordSkippedAndConverge：落 review_skipped(reason/attempt) 审计 + 收敛 DONE（两步同事务）")
    void shouldRecordSkippedAndConverge() {
        writer.recordSkippedAndConverge(TASK_ID, REPORT_TIME, 1, "executor_saturated");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_skipped"), eq(AgentRole.REVIEWER), isNull(),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("reason", "executor_saturated")
                .containsEntry("attempt", 1);
        verify(taskService).convergeFinalReportToDone(TASK_ID, REPORT_TIME);
    }
}
