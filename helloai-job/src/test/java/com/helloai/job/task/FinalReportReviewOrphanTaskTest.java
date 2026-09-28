package com.helloai.job.task;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FinalReportReviewOrphanTask 单元测试（§12.2 审查链三级容错 L3）。
 *
 * <p>覆盖：开关关闭不扫描、无孤儿不动作、超时孤儿经 CAS 收敛 DONE 并落审计事件、
 * CAS 未命中（版本已变）时跳过不落事件、单条异常不阻塞同轮其它记录。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinalReportReviewOrphanTask 最终报告 REVIEWING 兜底巡检")
class FinalReportReviewOrphanTaskTest {

    private static final Long TASK_ID = 7L;

    @Mock
    private TaskService taskService;
    @Mock
    private TaskTimelineService taskTimelineService;

    private final AgentDispatchProperties dispatchProperties = new AgentDispatchProperties();

    private FinalReportReviewOrphanTask task;

    @BeforeEach
    void setUp() {
        task = new FinalReportReviewOrphanTask(taskService, taskTimelineService, dispatchProperties);
        when(taskService.convergeFinalReportToDone(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("审查总开关关闭：不扫描（此时写回直接 DONE，本就不该有 REVIEWING）")
    void shouldNotScanWhenReviewDisabled() {
        dispatchProperties.setAutoFinalReportReviewEnabled(false);

        task.scan();

        verify(taskService, never()).listFinalReportReviewOrphans(anyInt(), anyInt());
    }

    @Test
    @DisplayName("无孤儿：只查一次，不做任何收敛")
    void shouldDoNothingWhenNoOrphan() {
        when(taskService.listFinalReportReviewOrphans(anyInt(), anyInt())).thenReturn(List.of());

        task.scan();

        verify(taskService, never()).convergeFinalReportToDone(any(), any());
        verify(taskTimelineService, never()).recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("超时孤儿：以 final_report_time 为锚点 CAS 收敛 DONE 并落 orphan_converged 事件")
    void shouldConvergeOrphanWithReportTimeAnchor() {
        Task orphan = orphan(TASK_ID, OffsetDateTime.now().minusMinutes(30));
        when(taskService.listFinalReportReviewOrphans(anyInt(), anyInt())).thenReturn(List.of(orphan));

        task.scan();

        // 锚点必须是该版报告的 final_report_time（陈旧守卫条件），不能是 now
        verify(taskService).convergeFinalReportToDone(TASK_ID, orphan.getFinalReportTime());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_orphan_converged"), eq(AgentRole.SYSTEM),
                isNull(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("thresholdSeconds", 300)
                .containsKey("stuckSeconds");
    }

    @Test
    @DisplayName("CAS 未命中（版本已变/已被兄弟链路收敛）：跳过且不落事件")
    void shouldSkipWhenCasMisses() {
        Task orphan = orphan(TASK_ID, OffsetDateTime.now().minusMinutes(30));
        when(taskService.listFinalReportReviewOrphans(anyInt(), anyInt())).thenReturn(List.of(orphan));
        when(taskService.convergeFinalReportToDone(any(), any())).thenReturn(false);

        task.scan();

        verify(taskService).convergeFinalReportToDone(any(), any());
        verify(taskTimelineService, never()).recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("单条缺 final_report_time：跳过该条，不做收敛")
    void shouldSkipOrphanWithoutReportTime() {
        Task orphan = orphan(TASK_ID, null);
        when(taskService.listFinalReportReviewOrphans(anyInt(), anyInt())).thenReturn(List.of(orphan));

        task.scan();

        verify(taskService, never()).convergeFinalReportToDone(any(), any());
    }

    @Test
    @DisplayName("查询异常：任务自身吞掉异常，不向调度器抛出")
    void shouldSwallowQueryException() {
        when(taskService.listFinalReportReviewOrphans(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        task.scan();

        verify(taskService, never()).convergeFinalReportToDone(any(), any());
    }

    private Task orphan(Long id, OffsetDateTime reportTime) {
        Task t = new Task();
        t.setId(id);
        t.setTitle("AI短剧快速上手图文教程（抖音竖屏）");
        t.setFinalReportTime(reportTime);
        return t;
    }
}
