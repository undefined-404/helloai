package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.base.NoCandidateAgentException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
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
    private SubTaskService subTaskService;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private AgentDispatchProperties agentDispatchProperties;

    private SubTaskPendingOrphanTask task;

    @BeforeEach
    void setUp() {
        when(executionProperties.isPendingOrphanEnabled()).thenReturn(true);
        when(executionProperties.getPendingOrphanThresholdMinutes()).thenReturn(30);
        when(executionProperties.getPendingOrphanBatchSize()).thenReturn(50);
        // 孤儿扫描前置依赖检查：默认无依赖即就绪，避免既有用例被 ready 守卫拦截
        when(subTaskService.isReady(any(SubTask.class))).thenReturn(true);
        // 时钟 C 缺省：阈值 10 / 间隔 120s / 抖动 0（确定性，便于断言）
        when(agentDispatchProperties.getNoCandidateMaxRounds()).thenReturn(10);
        when(agentDispatchProperties.getNoCandidateRetryIntervalSeconds()).thenReturn(120);
        when(agentDispatchProperties.getNoCandidateRetryJitterSeconds()).thenReturn(0);

        task = new SubTaskPendingOrphanTask(
                subTaskService, subTaskDispatchService,
                executionProperties, taskTimelineService, agentDispatchProperties);
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("enabled=false → 完全跳过（不查 DB）")
        void shouldSkipWhenDisabled() {
            when(executionProperties.isPendingOrphanEnabled()).thenReturn(false);

            task.scan();

            verifyNoInteractions(subTaskService);
            verifyNoInteractions(subTaskDispatchService);
        }

        @Test
        @DisplayName("无孤儿 → 不调用 dispatch")
        void shouldSkipWhenNoOrphans() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(null);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("子任务状态已被推进（非 PENDING）→ skip，不计入失败")
        void shouldSkipWhenStatusChanged() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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

        // ═══════════════════════════════════════════════════════════════
        //  P1-1：无候选属可自愈等待态 → 单独分流 + 落 timeline（不再静默）
        // ═══════════════════════════════════════════════════════════════

        @Test
        @DisplayName("P1-1: 无候选 → skipNoCandidate + 落 sub_task_no_candidate，不误报已重派")
        void shouldRecordNoCandidateAndNotReportDispatched() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(pendingSubTask(1L));

            // 选不出人：抛 NoCandidateAgentException（未消耗预算的等待态）
            doThrow(new NoCandidateAgentException("无可用候选 Agent: role=EXECUTOR"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            // 可观测性：落 sub_task_no_candidate（taskId=null，系统级等待态事件）
            verify(taskTimelineService).recordEvent(
                    isNull(), eq(1L), eq("sub_task_no_candidate"),
                    eq(AgentRole.SYSTEM), isNull(), anyMap());
            // 未被状态冲突分支截走（不落其它 skip 事件）
            verify(taskTimelineService, never()).recordEvent(
                    any(), any(), eq("sub_task_redispatch_skipped"), any(), any(), anyMap());
        }

        @Test
        @DisplayName("P1-1: 返回值 null（闸门拦截/退避）→ 走 skipGated，不落 no_candidate 事件")
        void shouldSkipGatedWithoutNoCandidateEvent() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));
            when(subTaskService.getById(1L)).thenReturn(pendingSubTask(1L));

            // 闸门拦截：本轮未派出，返回 null
            doReturn(null).when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));
            // 非「无候选」路径 → 不应落 no_candidate 事件
            verify(taskTimelineService, never()).recordEvent(
                    any(), any(), eq("sub_task_no_candidate"), any(), any(), anyMap());
        }

        // ═══════════════════════════════════════════════════════════════
        //  时钟 C（2026-10-05）：每子任务无候选重试节拍 + 长等待转人工
        // ═══════════════════════════════════════════════════════════════

        @Test
        @DisplayName("时钟C: 连续无候选 → rounds 累加并写回 nextDispatchAt（未达阈值）")
        void shouldAccumulateRoundsAndWriteNextDispatchAt() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));

            SubTask st = pendingSubTask(1L);
            // 上一轮已记 rounds=2；nextDispatchAt 已过期 ⇒ 放行本次重试
            Map<String, Object> prev = new HashMap<>();
            prev.put("rounds", 2);
            prev.put("nextDispatchAt", OffsetDateTime.now().minusSeconds(5).toString());
            st.setContext(new HashMap<>(Map.of("noCandidate", prev)));
            when(subTaskService.getById(1L)).thenReturn(st);

            doThrow(new NoCandidateAgentException("无可用候选 Agent: role=EXECUTOR"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            // rounds 2 → 3，写回 context.noCandidate（含未来 nextDispatchAt）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
            verify(subTaskService).updateContext(eq(1L), ctxCaptor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> noCandidate = (Map<String, Object>) ctxCaptor.getValue().get("noCandidate");
            assertThat(noCandidate).isNotNull();
            assertThat(noCandidate.get("rounds")).isEqualTo(3);
            assertThat(OffsetDateTime.parse(noCandidate.get("nextDispatchAt").toString()))
                    .isAfter(OffsetDateTime.now());
            // 未达阈值 → 不转人工介入
            verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        }

        @Test
        @DisplayName("时钟C: 达阈值 → markManualIntervention(no_candidate_long_wait)，不再写 nextDispatchAt")
        void shouldEscalateToManualInterventionWhenRoundsReachMax() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));

            SubTask st = pendingSubTask(1L);
            // rounds=9，阈值 10 ⇒ 本轮 +1 = 10 达阈值
            Map<String, Object> prev = new HashMap<>();
            prev.put("rounds", 9);
            prev.put("firstSeenAt", OffsetDateTime.now().minusMinutes(20).toString());
            prev.put("nextDispatchAt", OffsetDateTime.now().minusSeconds(5).toString());
            st.setContext(new HashMap<>(Map.of("noCandidate", prev)));
            when(subTaskService.getById(1L)).thenReturn(st);

            doThrow(new NoCandidateAgentException("无可用候选 Agent: role=EXECUTOR"))
                    .when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            // reason 必须为 no_candidate_long_wait，且带上 rounds=10 / waitedMs
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> extraCaptor = ArgumentCaptor.forClass(Map.class);
            verify(subTaskService).markManualIntervention(eq(1L), eq("no_candidate_long_wait"), extraCaptor.capture());
            assertThat(extraCaptor.getValue()).containsEntry("rounds", 10);
            assertThat(((Number) extraCaptor.getValue().get("waitedMs")).longValue()).isGreaterThan(0L);
            // 达阈值后不再写 nextDispatchAt
            verify(subTaskService, never()).updateContext(eq(1L), anyMap());
        }

        @Test
        @DisplayName("时钟C: 门控 —— now < nextDispatchAt → skipWaiting 且不调 dispatch")
        void shouldSkipWhenNextDispatchAtInFuture() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));

            SubTask st = pendingSubTask(1L);
            Map<String, Object> prev = new HashMap<>();
            prev.put("rounds", 2);
            // 节拍未到（未来 300s）
            prev.put("nextDispatchAt", OffsetDateTime.now().plusSeconds(300).toString());
            st.setContext(new HashMap<>(Map.of("noCandidate", prev)));
            when(subTaskService.getById(1L)).thenReturn(st);

            task.scan();

            // 节拍未到：不重试、不落事件、不改 context
            verify(subTaskDispatchService, never()).dispatchPendingSubTaskAuto(anyLong(), any());
            verify(taskTimelineService, never()).recordEvent(
                    any(), any(), eq("sub_task_no_candidate"), any(), any(), anyMap());
            verify(subTaskService, never()).updateContext(anyLong(), anyMap());
            verify(subTaskService, never()).markManualIntervention(anyLong(), anyString(), anyMap());
        }

        @Test
        @DisplayName("时钟C: 选中执行者 → 清除 context.noCandidate（计数归零）")
        void shouldClearNoCandidateWhenDispatched() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
                    .thenReturn(List.of(1L));

            SubTask st = pendingSubTask(1L);
            Map<String, Object> prev = new HashMap<>();
            prev.put("rounds", 2);
            prev.put("nextDispatchAt", OffsetDateTime.now().minusSeconds(5).toString());
            // 同 context 内含其它 key，须验证整体覆盖时不丢
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("noCandidate", prev);
            ctx.put("someKey", "keep-me");
            st.setContext(ctx);
            when(subTaskService.getById(1L)).thenReturn(st);
            doReturn(99L).when(subTaskDispatchService)
                    .dispatchPendingSubTaskAuto(eq(1L), eq(AgentRole.EXECUTOR));

            task.scan();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
            verify(subTaskService).updateContext(eq(1L), ctxCaptor.capture());
            assertThat(ctxCaptor.getValue()).doesNotContainKey("noCandidate");
            // 整体覆盖不丢其它 key
            assertThat(ctxCaptor.getValue()).containsEntry("someKey", "keep-me");
        }

        @Test
        @DisplayName("RuntimeException（非 BizException）→ 计入失败但继续其它")
        void shouldContinueOnRuntimeException() {
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), anyInt()))
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
        @DisplayName("service.listStalePendingWithoutExecutionRecord 使用配置阈值和批大小")
        void shouldPassConfiguredThresholdAndBatchToMapper() {
            when(executionProperties.getPendingOrphanThresholdMinutes()).thenReturn(45);
            when(executionProperties.getPendingOrphanBatchSize()).thenReturn(17);
            when(subTaskService.listStalePendingWithoutExecutionRecord(any(), eq(17)))
                    .thenReturn(List.of());

            task.scan();

            // offsetDate 参数无法直接 eq —— 改为用 any() 单独验证 limit=17
            verify(subTaskService, times(1))
                    .listStalePendingWithoutExecutionRecord(any(OffsetDateTime.class), eq(17));
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
