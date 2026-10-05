package com.helloai.it;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.TaskDispatchPort;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 子任务重派退避时钟（{@code sub_task.last_attempt_time}，V102）B 级真实库集成测试。
 *
 * <p><b>为什么必须补这条</b>（2026-10-05 修 804「换人/重新调度被退避窗口静默拦截」）：
 * 退避窗口原以 {@code sub_task.update_time} 为时钟，而 {@code block()}/{@code changeStatus()}/
 * {@code resume()}/{@code resetToPendingForDispatch()} 都会经 {@code updateById} 刷新它——
 * 「换人」入口先 {@code block()} 再重派，闸门读到被自身刷新的 now，{@code nextAllowed = now + 600s}
 * 必然拦截。此缺陷<b>只有真库</b>能证伪：单测里 {@code updateById} 被 mock，看不出「谁写了 update_time」。</p>
 *
 * <p>本测试锁定三条真库事实：</p>
 * <ol>
 *     <li>V102 迁移后 {@code sub_task.last_attempt_time} 列存在；</li>
 *     <li>状态流转（block/changeStatus）刷新 {@code update_time} 但<b>不</b>触碰
 *         {@code last_attempt_time}；退避时钟只由 {@code incrementAttemptTotal} 推进、
 *         由 {@code resetAttemptTotal} 清空；</li>
 *     <li>退避窗口内 BLOCKED 重派被拦截且落 {@code sub_task_redispatch_skipped} 时间线
 *         （不再静默）；窗口过后放行。</li>
 * </ol>
 *
 * <p><b>真实边界</b>：PostgreSQL / Redis / RabbitMQ 均 Testcontainers（与
 * {@link AbstractItTestBase} 同口径），Flyway 按真实迁移脚本建表，SQL 全真实执行。</p>
 */
@DisplayName("子任务退避时钟（V102）真实库集成验证")
class SubTaskBackoffClockIT extends AbstractItTestBase {

    // 主键固定 9xxx 段 + it- 前缀，与内建 seed 隔离（AbstractItTestBase 口径）
    private static final long TASK = 9060L;
    private static final long SUB = 9360L;
    private static final long PREFERRED_AGENT = 12345L;

    @Autowired
    private SubTaskService subTaskService;

    @Autowired
    private SubTaskDispatchService subTaskDispatchService;

    @Autowired
    private SubTaskMapper subTaskMapper;

    /** 隔离出分配链：本 IT 只验证闸门判定与时钟语义，不关心 assignNext 的真实选人。 */
    @MockBean
    private TaskDispatchPort taskDispatchPort;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM task_timeline WHERE sub_task_id = ?", SUB);
        jdbcTemplate.update("DELETE FROM sub_task WHERE id = ?", SUB);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK);
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-backoff-task', 'IN_PROGRESS', 'it', 'it')
                """, TASK);
    }

    private void seedSubTask(String status, int attemptTotal, OffsetDateTime lastAttemptTime) {
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, attempt_total, last_attempt_time, create_by, update_by)
                VALUES (?, ?, ?, ?, ?, ?, 'it', 'it')
                """, SUB, TASK, "it-backoff-sub", status, attemptTotal, lastAttemptTime);
    }

    // ==================== 断言辅助 ====================

    private String readStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM sub_task WHERE id = ?", String.class, SUB);
    }

    private int readAttemptTotal() {
        Integer n = jdbcTemplate.queryForObject("SELECT attempt_total FROM sub_task WHERE id = ?", Integer.class, SUB);
        return n != null ? n : -1;
    }

    private Instant readLastAttemptTimeInstant() {
        Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT last_attempt_time FROM sub_task WHERE id = ?", Timestamp.class, SUB);
        return ts == null ? null : ts.toInstant();
    }

    private Instant readUpdateTimeInstant() {
        Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT update_time FROM sub_task WHERE id = ?", Timestamp.class, SUB);
        return ts == null ? null : ts.toInstant();
    }

    private int countTimelineEvents(String eventType) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_timeline WHERE sub_task_id = ? AND event_type = ?",
                Integer.class, SUB, eventType);
        return n != null ? n : 0;
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("V102：sub_task.last_attempt_time 列存在")
    void lastAttemptTimeColumnExists() {
        assertTrue(columnExists("sub_task", "last_attempt_time"),
                "V102: sub_task.last_attempt_time 应存在");
    }

    @Test
    @DisplayName("退避时钟改绑：block 刷新 update_time 但不动 last_attempt_time；increment 推进、reset 清空")
    void backoffClockIsIndependentOfStateTransitions() {
        OffsetDateTime base = OffsetDateTime.now().minusHours(2).withNano(0);
        seedSubTask("IN_PROGRESS", 1, base);

        // ① block()（→ changeStatus → updateById）不得刷新退避时钟
        subTaskService.block(SUB, "人工判定执行停滞，改派新执行者", null);
        assertEquals(base.toInstant(), readLastAttemptTimeInstant(),
                "block() 不得刷新 last_attempt_time（否则「换人」被自身刚刷新的时钟拦截）");

        // ② update_time 确实被 block 刷新了 —— 证明差异来源是「换了列」，而非「没写库」
        Instant updateTime = readUpdateTimeInstant();
        assertNotNull(updateTime);
        assertTrue(updateTime.isAfter(base.toInstant()),
                "block() 应刷新 update_time（这正是历史拦截根因）");

        // ③ incrementAttemptTotal 原子写入 last_attempt_time 并累加计数
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        subTaskMapper.incrementAttemptTotal(SUB, now);
        assertEquals(now.toInstant(), readLastAttemptTimeInstant(),
                "incrementAttemptTotal 应同批写入 last_attempt_time");
        assertEquals(2, readAttemptTotal());

        // ④ resetAttemptTotal（死信人工重派）清空时钟，避免残留上一轮退避窗口
        subTaskMapper.resetAttemptTotal(SUB, OffsetDateTime.now());
        assertNull(readLastAttemptTimeInstant(), "resetAttemptTotal 应清空 last_attempt_time");
        assertEquals(0, readAttemptTotal());
    }

    @Test
    @DisplayName("804：退避窗口内 BLOCKED 重派被拦截 → 返回 skipped + 状态不变 + 落 sub_task_redispatch_skipped")
    void blockedRedispatchWithinBackoffIsSkippedAndObservable() {
        seedSubTask("BLOCKED", 1, OffsetDateTime.now().withNano(0));

        SubTaskDispatchService.RedispatchResult result =
                subTaskDispatchService.dispatchBlockedSubTask(SUB, PREFERRED_AGENT);

        assertFalse(result.applied(), "退避窗口内应被拦截（applied=false）");
        assertEquals("backoff", result.reason());
        assertNotNull(result.nextAllowed(), "backoff 应带 nextAllowed 供前端提示");
        // 拦截不消耗预算、不改状态、不派发
        assertEquals("BLOCKED", readStatus());
        assertEquals(1, readAttemptTotal());
        // 可观测性：闸门拦截不再静默，落 sub_task_redispatch_skipped
        assertEquals(1, countTimelineEvents("sub_task_redispatch_skipped"));
        verify(taskDispatchPort, times(0)).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("804：退避窗口已过 → 重派放行（不再被自己刷新的时钟拦截）+ 计数/时钟推进")
    void blockedRedispatchAfterBackoffProceeds() {
        // last_attempt_time 早在 1 小时前（第 1 档退避仅 60s）→ 窗口已过
        seedSubTask("BLOCKED", 1, OffsetDateTime.now().minusHours(1).withNano(0));

        SubTaskDispatchService.RedispatchResult result =
                subTaskDispatchService.dispatchBlockedSubTask(SUB, PREFERRED_AGENT);

        assertTrue(result.applied(), "退避窗口已过应放行（applied=true）");
        // BLOCKED → PENDING（resetToPendingForDispatch）+ 累加计数 + 推进时钟
        assertEquals("PENDING", readStatus());
        assertEquals(2, readAttemptTotal());
        assertNotNull(readLastAttemptTimeInstant(), "放行应经 incrementAttemptTotal 推进时钟");
        verify(taskDispatchPort, times(1)).assignNext(eq(PREFERRED_AGENT), eq(SUB), any());
    }
}
