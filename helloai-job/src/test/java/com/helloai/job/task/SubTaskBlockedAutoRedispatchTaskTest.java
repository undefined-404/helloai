package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskDispatchService.RedispatchResult;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SubTaskBlockedAutoRedispatchTask} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>enabled=false / 无停滞 BLOCKED → noop</li>
 *   <li>单条 / 多条 → 逐条 dispatchBlockedSubTaskAuto，单条失败不中断</li>
 *   <li>子任务不存在 / 状态已变更 / 依赖未就绪 → skip</li>
 *   <li>返回值分流：applied / no_candidate / manual_intervention / 闸门（退避/熔断）</li>
 *   <li>BizException（并发状态冲突）→ skip 不计入失败；RuntimeException → 计入失败</li>
 *   <li>自报阻塞开关透传 + 阈值/批大小透传</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskBlockedAutoRedispatchTask")
class SubTaskBlockedAutoRedispatchTaskTest {

    @Mock
    private SubTaskService subTaskService;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private AgentExecutionProperties executionProperties;

    private SubTaskBlockedAutoRedispatchTask task;

    @BeforeEach
    void setUp() {
        when(executionProperties.isBlockedRedispatchEnabled()).thenReturn(true);
        when(executionProperties.getBlockedRedispatchThresholdMinutes()).thenReturn(5);
        when(executionProperties.getBlockedRedispatchBatchSize()).thenReturn(50);
        when(executionProperties.isBlockedRedispatchSelfReportedEnabled()).thenReturn(true);
        // 依赖就绪默认放行，避免既有用例被 ready 守卫拦截
        when(subTaskService.isReady(any(SubTask.class))).thenReturn(true);

        task = new SubTaskBlockedAutoRedispatchTask(
                subTaskService, subTaskDispatchService, executionProperties);
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("enabled=false → 完全跳过（不查 DB）")
        void shouldSkipWhenDisabled() {
            when(executionProperties.isBlockedRedispatchEnabled()).thenReturn(false);

            task.scan();

            verifyNoInteractions(subTaskService);
            verifyNoInteractions(subTaskDispatchService);
        }

        @Test
        @DisplayName("无停滞 BLOCKED → 不调用 dispatch")
        void shouldSkipWhenNoStaleBlocked() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of());

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchBlockedSubTaskAuto(anyLong(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("BLOCKED 自动重派")
    class BlockedRedispatch {

        @Test
        @DisplayName("单条 → dispatchBlockedSubTaskAuto 一次，透传自报阻塞开关")
        void shouldRedispatchSingleBlocked() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(1L), eq(true)))
                    .thenReturn(RedispatchResult.ofApplied());

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchBlockedSubTaskAuto(eq(1L), eq(true));
        }

        @Test
        @DisplayName("多条 → 逐条 dispatch")
        void shouldRedispatchMultipleBlocked() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L, 3L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(blockedSubTask(2L));
            when(subTaskService.getById(3L)).thenReturn(blockedSubTask(3L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(anyLong(), anyBoolean()))
                    .thenReturn(RedispatchResult.ofApplied());

            task.scan();

            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(1L), eq(true));
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(2L), eq(true));
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(3L), eq(true));
        }

        @Test
        @DisplayName("子任务不存在（getById 返 null）→ skip")
        void shouldSkipWhenSubTaskNotFound() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(null);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchBlockedSubTaskAuto(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("状态已被推进（非 BLOCKED）→ skip，不计入失败")
        void shouldSkipWhenStatusChanged() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            SubTask st = blockedSubTask(1L);
            st.setStatus(SubTaskStatus.PENDING);  // 已被人工/其它路径处置
            when(subTaskService.getById(1L)).thenReturn(st);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchBlockedSubTaskAuto(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("依赖未就绪 → skip 不重派（等上游解锁）")
        void shouldSkipWhenNotReady() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskService.isReady(any(SubTask.class))).thenReturn(false);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchBlockedSubTaskAuto(anyLong(), anyBoolean());
        }

        @Test
        @DisplayName("返回值 manual_intervention → skip（等人工拍板）")
        void shouldSkipWhenManualIntervention() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(1L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofSkipped("manual_intervention", null));

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
        }

        @Test
        @DisplayName("返回值 no_candidate → skip（可自愈等待态，不消耗预算）")
        void shouldSkipWhenNoCandidate() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(1L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofSkipped("no_candidate", null));

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
        }

        @Test
        @DisplayName("返回值 backoff（退避窗口未过）→ skip，不中断同轮")
        void shouldSkipWhenBackoff() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(blockedSubTask(2L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(1L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofSkipped("backoff", OffsetDateTime.now().plusSeconds(120)));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(2L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofApplied());

            task.scan();

            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(2L), anyBoolean());
        }

        @Test
        @DisplayName("BizException（状态冲突）→ skip 不影响其它子任务")
        void shouldContinueOnBizException() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(blockedSubTask(2L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(2L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofApplied());

            doThrow(new BizException("只有 BLOCKED 状态的子任务才能自动重派"))
                    .when(subTaskDispatchService)
                    .dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());

            task.scan();

            // 两条都被尝试，BizException 视为并发冲突不中断
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(2L), anyBoolean());
        }

        @Test
        @DisplayName("RuntimeException（非 BizException）→ 计入失败但继续其它")
        void shouldContinueOnRuntimeException() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(blockedSubTask(2L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(2L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofApplied());

            doThrow(new RuntimeException("synthetic unexpected failure"))
                    .when(subTaskDispatchService)
                    .dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());

            task.scan();

            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(2L), anyBoolean());
        }

        @Test
        @DisplayName("混合：BizException + RuntimeException + 正常 三者互不干扰")
        void shouldHandleMixedFailures() {
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L, 3L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(blockedSubTask(2L));
            when(subTaskService.getById(3L)).thenReturn(blockedSubTask(3L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(2L), anyBoolean()))
                    .thenReturn(RedispatchResult.ofApplied());

            doThrow(new BizException("conflict"))
                    .when(subTaskDispatchService).dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
            doThrow(new RuntimeException("kaboom"))
                    .when(subTaskDispatchService).dispatchBlockedSubTaskAuto(eq(3L), anyBoolean());

            task.scan();

            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(1L), anyBoolean());
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(2L), anyBoolean());
            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(3L), anyBoolean());
        }

        @Test
        @DisplayName("自报阻塞开关关闭 → 透传 false")
        void shouldPassSelfReportedFlagFalse() {
            when(executionProperties.isBlockedRedispatchSelfReportedEnabled()).thenReturn(false);
            when(subTaskService.listStaleBlockedSubTaskIds(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(blockedSubTask(1L));
            when(subTaskDispatchService.dispatchBlockedSubTaskAuto(eq(1L), eq(false)))
                    .thenReturn(RedispatchResult.ofApplied());

            task.scan();

            verify(subTaskDispatchService, times(1)).dispatchBlockedSubTaskAuto(eq(1L), eq(false));
        }

        @Test
        @DisplayName("service 使用配置阈值与批大小")
        void shouldPassConfiguredThresholdAndBatchToMapper() {
            when(executionProperties.getBlockedRedispatchBatchSize()).thenReturn(17);
            when(subTaskService.listStaleBlockedSubTaskIds(any(), eq(17)))
                    .thenReturn(List.of());

            task.scan();

            // cutoff 参数无法直接 eq —— 单独验证 limit=17
            verify(subTaskService, times(1))
                    .listStaleBlockedSubTaskIds(any(OffsetDateTime.class), eq(17));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    private static SubTask blockedSubTask(Long id) {
        SubTask s = new SubTask();
        s.setId(id);
        s.setStatus(SubTaskStatus.BLOCKED);
        return s;
    }
}
