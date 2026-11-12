package com.helloai.core.planner;

import com.helloai.common.constant.PlanAttemptAction;
import com.helloai.common.constant.PlanAttemptStatus;
import com.helloai.core.planner.entity.PlanAttempt;
import com.helloai.core.planner.mapper.PlanAttemptMapper;
import com.helloai.core.planner.service.impl.PlanAttemptServiceImpl;
import com.helloai.core.task.service.TaskService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `TD-16b` 尝试栅栏（Outbox + 五态）的单元测试。
 *
 * <p><b>这里钉的三件事</b>（都是「没测到就会静默坏」的）：</p>
 * <ol>
 *   <li><b>T1 原子</b>：任务状态 CAS 落空时**不得**登记 attempt。否则会出现
 *       「有一份别人在干的活，我也登记了一份」——派发器随后会重复触发。</li>
 *   <li><b>防悬挂</b>：{@code casSucceed} 影响 0 行必须返回 {@code false}，
 *       让调用方放弃写业务结果。返回 {@code true} 会让苏醒的旧尝试覆盖新尝试的成果。</li>
 *   <li><b>悬挂回收只在 CAS 赢时计数</b>：多实例同时扫到同一条时只有一个能转成功，
 *       计数不能按「扫到几条」算，否则监控会把一次悬挂报成 N 次。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlanAttemptService（TD-16b 栅栏）")
class PlanAttemptServiceImplTest {

    private static final Long TASK_ID = 100L;
    private static final OffsetDateTime FIXED_NOW = OffsetDateTime.parse("2026-11-08T20:00:00+08:00");

    @Mock
    private PlanAttemptMapper planAttemptMapper;

    @Mock
    private TaskService taskService;

    private PlanAttemptServiceImpl service;

    @BeforeEach
    void setUp() {
        // 时间固定在测试里：租约边界的用例不该靠 sleep 造场景。
        service = new PlanAttemptServiceImpl(planAttemptMapper, taskService) {
            @Override
            protected OffsetDateTime now() {
                return FIXED_NOW;
            }
        };
    }

    @Test
    @DisplayName("T1：CAS 抢到任务 ⇒ 登记尝试（PENDING / attemptNo=1 / 带租约）")
    void shouldRegisterAttemptWhenTaskCasSucceeds() {
        when(taskService.beginDecompose(TASK_ID, 10)).thenReturn("attempt-key-1");
        when(planAttemptMapper.findLatestByBatchKey("attempt-key-1")).thenReturn(null);

        String attemptId = service.beginDecompose(TASK_ID, 10);

        assertThat(attemptId).isEqualTo("attempt-key-1");
        ArgumentCaptor<PlanAttempt> captor = ArgumentCaptor.forClass(PlanAttempt.class);
        verify(planAttemptMapper).insert(captor.capture());
        PlanAttempt inserted = captor.getValue();
        assertThat(inserted.getTaskId()).isEqualTo(TASK_ID);
        assertThat(inserted.getBatchKey()).isEqualTo("attempt-key-1");
        assertThat(inserted.getAction()).isEqualTo(PlanAttemptAction.DECOMPOSE);
        assertThat(inserted.getStatus()).isEqualTo(PlanAttemptStatus.PENDING);
        assertThat(inserted.getAttemptNo()).isEqualTo(1);
        assertThat(inserted.getLeaseUntil()).isEqualTo(FIXED_NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("T1：CAS 落空（任务已被别的请求推进）⇒ 不登记任何 attempt")
    void shouldNotRegisterAttemptWhenTaskCasLoses() {
        when(taskService.beginDecompose(TASK_ID, 10)).thenReturn(null);

        String attemptId = service.beginDecompose(TASK_ID, 10);

        assertThat(attemptId).isNull();
        // 这是 T1 原子性的另一半：没有「有人干活但状态没改」，也没有「状态没改却登记了活」。
        verify(planAttemptMapper, never()).insert(any(PlanAttempt.class));
    }

    @Test
    @DisplayName("防悬挂：收尾 CAS 落空 ⇒ 返回 false（调用方必须放弃写业务结果）")
    void shouldReturnFalseWhenSucceedCasMisses() {
        when(planAttemptMapper.casSucceed(anyLong(), anyInt(), any(), anyString(), any()))
                .thenReturn(0);

        boolean ok = service.succeed(11L, 2, 7, "completed");

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("防悬挂：收尾 CAS 命中 ⇒ 返回 true，且把 expectedCount 一起落库")
    void shouldReturnTrueAndCarryExpectedCountWhenSucceedCasHits() {
        when(planAttemptMapper.casSucceed(anyLong(), anyInt(), any(), anyString(), any()))
                .thenReturn(1);

        boolean ok = service.succeed(11L, 2, 7, "completed");

        assertThat(ok).isTrue();
        verify(planAttemptMapper).casSucceed(eq(11L), eq(2), eq(7), eq("completed"), eq(FIXED_NOW));
    }

    @Test
    @DisplayName("TD-16e-2：期望条数写入成功 ⇒ 返回 true（撞键恢复要靠它判完整）")
    void shouldRecordExpectedCountWhenAttemptStillValid() {
        when(planAttemptMapper.recordExpectedCount(11L, 2, 7, FIXED_NOW)).thenReturn(1);

        boolean ok = service.recordExpectedCount(11L, 2, 7);

        assertThat(ok).isTrue();
        verify(planAttemptMapper).recordExpectedCount(11L, 2, 7, FIXED_NOW);
    }

    @Test
    @DisplayName("TD-16e-2：attempt_no 已失效 ⇒ 返回 false（不得改写当前有效尝试的期望值）")
    void shouldReturnFalseWhenExpectedCountCasMisses() {
        when(planAttemptMapper.recordExpectedCount(anyLong(), anyInt(), anyInt(), any())).thenReturn(0);

        boolean ok = service.recordExpectedCount(11L, 1, 7);

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("TD-16e-2：0 条 / 空参不写库（期望 0 条会让「一条没落」被判成完整，方向正好反了）")
    void shouldSkipExpectedCountWriteForZeroOrNull() {
        assertThat(service.recordExpectedCount(11L, 2, 0)).isFalse();
        assertThat(service.recordExpectedCount(null, 2, 7)).isFalse();
        assertThat(service.recordExpectedCount(11L, null, 7)).isFalse();
        verify(planAttemptMapper, never()).recordExpectedCount(anyLong(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("悬挂回收：只统计 CAS 赢的条数（多实例同时扫到也只算一次）")
    void shouldCountReclaimedOnlyWhenCasWins() {
        PlanAttempt a = attempt(1L, "k1");
        PlanAttempt b = attempt(2L, "k2");
        when(planAttemptMapper.listHanging(FIXED_NOW, 10)).thenReturn(List.of(a, b));
        when(planAttemptMapper.casMarkHanging(eq(1L), any(), eq(FIXED_NOW))).thenReturn(1);
        // 另一个实例已经先转成功了
        when(planAttemptMapper.casMarkHanging(eq(2L), any(), eq(FIXED_NOW))).thenReturn(0);

        int reclaimed = service.reclaimHanging(10);

        assertThat(reclaimed).isEqualTo(1);
    }

    @Test
    @DisplayName("登记幂等：同一 batchKey 已有行 ⇒ 复用，不重复插入")
    void shouldReuseExistingAttemptForSameBatchKey() {
        PlanAttempt existing = attempt(9L, "k-dup");
        when(planAttemptMapper.findLatestByBatchKey("k-dup")).thenReturn(existing);

        PlanAttempt got = service.begin(TASK_ID, "k-dup", PlanAttemptAction.DECOMPOSE, FIXED_NOW);

        assertThat(got.getId()).isEqualTo(9L);
        verify(planAttemptMapper, never()).insert(any(PlanAttempt.class));
    }

    @Test
    @DisplayName("按幂等键查不到 ⇒ 返回空 Optional（调用方据此跳过栅栏而不是补造记录）")
    void shouldReturnEmptyWhenBatchKeyNotFound() {
        when(planAttemptMapper.findLatestByBatchKey(anyString())).thenReturn(null);

        Optional<PlanAttempt> got = service.findLatestByBatchKey("nope");

        assertThat(got).isEmpty();
    }

    private PlanAttempt attempt(Long id, String batchKey) {
        PlanAttempt a = new PlanAttempt();
        a.setId(id);
        a.setBatchKey(batchKey);
        a.setTaskId(TASK_ID);
        a.setStatus(PlanAttemptStatus.RUNNING);
        a.setAttemptNo(1);
        return a;
    }
}
