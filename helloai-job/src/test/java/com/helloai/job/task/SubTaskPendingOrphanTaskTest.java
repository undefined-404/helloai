package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SubTaskPendingOrphanTask} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>enabled=false / 无孤儿 → noop</li>
 *   <li>单条孤儿 → dispatchPendingSubTaskAuto</li>
 *   <li>多条孤儿 → 逐条重派，单条失败不中断</li>
 *   <li>子任务不存在 / 状态已变更 → skip</li>
 *   <li>BizException（并发状态冲突）→ skip 不计入失败</li>
 *   <li>RuntimeException → 计入失败但不影响其它</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubTaskPendingOrphanTask")
class SubTaskPendingOrphanTaskTest {

    @Mock
    private SubTaskMapper subTaskMapper;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private AgentExecutionProperties executionProperties;

    private SubTaskPendingOrphanTask task;

    @BeforeEach
    void setUp() {
        when(executionProperties.isPendingOrphanEnabled()).thenReturn(true);
        when(executionProperties.getPendingOrphanThresholdMinutes()).thenReturn(30);
        when(executionProperties.getPendingOrphanBatchSize()).thenReturn(50);
        // 孤儿扫描前置依赖检查：默认无依赖即就绪，避免既有用例被 ready 守卫拦截
        when(subTaskService.isReady(any(SubTask.class))).thenReturn(true);

        task = new SubTaskPendingOrphanTask(
                subTaskMapper, subTaskService, subTaskDispatchService,
                executionProperties);
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("enabled=false → 完全跳过（不查 DB）")
        void shouldSkipWhenDisabled() {
            when(executionProperties.isPendingOrphanEnabled()).thenReturn(false);

            task.scan();

            verifyNoInteractions(subTaskMapper);
            verifyNoInteractions(subTaskService);
            verifyNoInteractions(subTaskDispatchService);
        }

        @Test
        @DisplayName("无孤儿 → 不调用 dispatch")
        void shouldSkipWhenNoOrphans() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of());

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("孤儿回收")
    class OrphanRecovery {

        @Test
        @DisplayName("单条孤儿 → 按 EXECUTOR 角色 dispatch 一次")
        void shouldRedispatchSingleOrphan() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));

            SubTask st = pendingSubTask(1L);
            when(subTaskService.getById(1L)).thenReturn(st);

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("多条孤儿 → 逐条 dispatch，每次都按 EXECUTOR")
        void shouldRedispatchMultipleOrphans() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L, 3L));

            when(subTaskService.getById(1L)).thenReturn(pendingSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(pendingSubTask(2L));
            when(subTaskService.getById(3L)).thenReturn(pendingSubTask(3L));

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(2L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(3L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("子任务被其它路径删除（getById 返 null）→ skip")
        void shouldSkipWhenSubTaskNotFound() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(null);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("子任务状态已被推进（非 PENDING）→ skip，不计入失败")
        void shouldSkipWhenStatusChanged() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            SubTask st = pendingSubTask(1L);
            st.setStatus(SubTaskStatus.ASSIGNED);  // 已被其它路径推进
            when(subTaskService.getById(1L)).thenReturn(st);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("已标记人工介入 → skip 不自动重派")
        void shouldSkipWhenManualInterventionMarked() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            SubTask st = pendingSubTask(1L);
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("manualIntervention", Map.of("reason", "fallback_skip_execution_dense"));
            st.setContext(ctx);
            when(subTaskService.getById(1L)).thenReturn(st);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("未标记人工介入但带其它 context → 仍正常重派")
        void shouldRedispatchWhenContextWithoutManualIntervention() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            SubTask st = pendingSubTask(1L);
            st.setContext(Map.of("someKey", "someValue"));
            when(subTaskService.getById(1L)).thenReturn(st);

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("BizException（状态冲突）→ skip 不影响其它子任务")
        void shouldContinueOnBizException() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));

            when(subTaskService.getById(1L)).thenReturn(pendingSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(pendingSubTask(2L));

            // 第一条被 BizException 中断（典型：刚好被并发路径 claim）
            doThrow(new BizException("只有 PENDING 状态的子任务才能自动分配"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            // 两条都被尝试，BizException 视为并发冲突不中断
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(2L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("RuntimeException（非 BizException）→ 计入失败但继续其它")
        void shouldContinueOnRuntimeException() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L));

            when(subTaskService.getById(1L)).thenReturn(pendingSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(pendingSubTask(2L));

            doThrow(new RuntimeException("synthetic unexpected failure"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            // 即便第 1 条失败，第 2 条仍被尝试
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(2L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("混合：BizException + RuntimeException + 正常 三者互不干扰")
        void shouldHandleMixedFailures() {
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L, 2L, 3L));

            when(subTaskService.getById(1L)).thenReturn(pendingSubTask(1L));
            when(subTaskService.getById(2L)).thenReturn(pendingSubTask(2L));
            when(subTaskService.getById(3L)).thenReturn(pendingSubTask(3L));

            // 第 1 条：并发状态冲突（业务异常）
            doThrow(new BizException("conflict"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
            // 第 3 条：真正的意外异常
            doThrow(new RuntimeException("kaboom"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(3L), eq(AgentRole.EXECUTOR));

            task.scan();

            // 三条都被尝试，没有中断
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(2L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(3L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("mapper.selectStalePendingWithoutExecutionRecord 使用配置阈值和批大小")
        void shouldPassConfiguredThresholdAndBatchToMapper() {
            when(executionProperties.getPendingOrphanThresholdMinutes()).thenReturn(45);
            when(executionProperties.getPendingOrphanBatchSize()).thenReturn(17);
            when(subTaskMapper.selectStalePendingWithoutExecutionRecord(any(), eq(17)))
                    .thenReturn(List.of());

            task.scan();

            // offsetDate 参数无法直接 eq —— 改为用 any() 单独验证 limit=17
            verify(subTaskMapper, times(1))
                    .selectStalePendingWithoutExecutionRecord(any(OffsetDateTime.class), eq(17));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    private static SubTask pendingSubTask(Long id) {
        SubTask s = new SubTask();
        s.setId(id);
        s.setStatus(SubTaskStatus.PENDING);
        return s;
    }
}
