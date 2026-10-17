package com.helloai.it;

import com.helloai.core.agent.port.TaskDispatchPort;
import com.helloai.job.task.SubTaskBlockedAutoRedispatchTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.sql.Timestamp;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BLOCKED 子任务自动重派巡检（plan §10.7.6 方案 b）B 级真实库集成测试。
 *
 * <p><b>为什么必须补这条</b>：{@code REF-8.6} 引入取消树后，「重启撞上在途任务」会把子任务落
 * {@code BLOCKED}（payload {@code agent_runtime_cancelled}），而 {@code dispatchBlockedSubTask}
 * 此前<b>只有人工入口</b>、{@code SubTaskPendingOrphanTask} 只收 {@code PENDING}
 * ⇒ 无人干预时该子任务永久 {@code BLOCKED}、下游依赖恒 {@code PENDING}，整条流水线停摆。
 * 巡检任务 {@code SubTaskBlockedAutoRedispatchTask} 是补齐这一缺口的唯一自动通道，
 * 其单测全部 mock 掉 Mapper / Service，<b>看不出</b>三件只有真库能证的事实：</p>
 * <ol>
 *     <li>「停滞」判据（{@code status='BLOCKED' AND update_time < cutoff AND deleted=0}）
 *         在真实 PG 上按预期命中 / 不命中——{@code update_time} 由 BEFORE UPDATE 触发器维护，
 *         mock 层完全看不到这一列的真实写入时机；</li>
 *     <li>命中后经真实 {@code SubTaskDispatchService.dispatchBlockedSubTaskAuto} 走完
 *         角色推导 → 真实 agent 表选人 → 退避闸门 → {@code BLOCKED→PENDING} 状态流转，
 *         并真实推进重派预算 {@code attempt_total} 与退避时钟 {@code last_attempt_time}；</li>
 *     <li>三类「不该动」的 BLOCKED 在真实链路上确实一步都不走（阈值内 / 依赖未就绪 /
 *         人工介入标记），既不消耗预算也不派单。</li>
 * </ol>
 *
 * <p><b>真实边界</b>：PG / Redis / RabbitMQ 均 Testcontainers，Flyway 按真实迁移脚本建表；
 * 唯一被隔离的是<b>出站派单端</b> {@link TaskDispatchPort}（{@code assignNext} 会向 MQ
 * 投递真实执行命令），与 {@code SubTaskBackoffClockIT} 同口径——本 IT 验证「巡检是否把
 * 子任务正确推进调度链」，不验证 MQ 投递本身（由 {@code MqExecutionCommandConsumerIT} 覆盖）。</p>
 *
 * <p><b>时序</b>：{@code ItTestApplication} 刻意不启用 {@code @EnableScheduling}
 * ⇒ 巡检不会自触发，用例直接调 {@code scan()}，断言前后状态确定。</p>
 */
@DisplayName("BLOCKED 子任务自动重派巡检真实库集成验证")
class SubTaskBlockedAutoRedispatchIT extends AbstractItTestBase {

    // 主键固定 9xxx 段 + it- 前缀，与内建 seed 隔离（AbstractItTestBase 口径）
    private static final long TASK = 9070L;
    private static final long SUB_STALE = 9370L;
    private static final long SUB_FRESH = 9371L;
    private static final long SUB_DEPENDENCY = 9372L;
    private static final long SUB_MANUAL = 9373L;
    private static final long DEP_BLOCKING = 9374L;
    private static final long AGENT = 12350L;

    /** 停滞判据：默认阈值 5 分钟（{@code blocked-redispatch-threshold-minutes}），取 10 分钟留余量。 */
    private static final OffsetDateTime STALE = OffsetDateTime.now().minusMinutes(10).withNano(0);

    @Autowired
    private SubTaskBlockedAutoRedispatchTask autoRedispatchTask;

    /** 隔离出站派单端：{@code assignNext} 会真投 MQ 执行命令，属本 IT 范围外的外部边界。 */
    @MockBean
    private TaskDispatchPort taskDispatchPort;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM task_timeline WHERE sub_task_id IN (?, ?, ?, ?, ?)",
                SUB_STALE, SUB_FRESH, SUB_DEPENDENCY, SUB_MANUAL, DEP_BLOCKING);
        jdbcTemplate.update("DELETE FROM sub_task WHERE id IN (?, ?, ?, ?, ?)",
                SUB_STALE, SUB_FRESH, SUB_DEPENDENCY, SUB_MANUAL, DEP_BLOCKING);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK);
        jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT);
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-blocked-redispatch-task', 'IN_PROGRESS', 'it', 'it')
                """, TASK);
        // 唯一候选执行者：CLI_CLIENT + ONLINE + 心跳新鲜（选人链的 isHeartbeatFresh 前置）
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, online_status, last_seen_time,
                                   create_by, update_by)
                VALUES (?, 'it-blocked-agent', 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'ONLINE', ?, 'it', 'it')
                """, AGENT, OffsetDateTime.now());
    }

    /**
     * 直插子任务：{@code update_time} 显式写入（该列由 BEFORE UPDATE 触发器维护，INSERT 不受影响），
     * 用于精确控制「是否已停滞超过阈值」。
     */
    private void seedSubTask(long id, String status, OffsetDateTime updateTime,
                             String dependsOnJson, String contextJson) {
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, update_time, depends_on, context,
                                      create_by, update_by)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 'it', 'it')
                """, id, TASK, "it-blocked-sub-" + id, status, updateTime, dependsOnJson, contextJson);
    }

    // ==================== 断言辅助 ====================

    private String readStatus(long subTaskId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sub_task WHERE id = ?", String.class, subTaskId);
    }

    private int readAttemptTotal(long subTaskId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT attempt_total FROM sub_task WHERE id = ?", Integer.class, subTaskId);
        return n != null ? n : -1;
    }

    private Timestamp readLastAttemptTime(long subTaskId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_attempt_time FROM sub_task WHERE id = ?", Timestamp.class, subTaskId);
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("停滞 BLOCKED → 巡检一轮后真的被推进调度链（BLOCKED→PENDING + 预算/时钟推进 + 派单一次）")
    void staleBlockedIsRedispatchedEndToEnd() {
        seedSubTask(SUB_STALE, "BLOCKED", STALE, "[]", null);

        autoRedispatchTask.scan();

        // 缺口的本质 = 这条流水线能自己动起来；下面四项共同证明「动了」
        assertEquals("PENDING", readStatus(SUB_STALE), "巡检应把停滞 BLOCKED 推出阻塞态");
        assertEquals(1, readAttemptTotal(SUB_STALE), "重派应消耗一次预算（attempt_total 0→1）");
        assertNotNull(readLastAttemptTime(SUB_STALE), "重派应同源推进退避时钟 last_attempt_time");
        verify(taskDispatchPort, times(1)).assignNext(eq(AGENT), eq(SUB_STALE), any());
    }

    @Test
    @DisplayName("阈值内 BLOCKED 不动（留人工处置窗口，不与人工操作抢跑）")
    void freshBlockedWithinThresholdIsIgnored() {
        seedSubTask(SUB_FRESH, "BLOCKED", OffsetDateTime.now(), "[]", null);

        autoRedispatchTask.scan();

        assertEquals("BLOCKED", readStatus(SUB_FRESH), "未过阈值的 BLOCKED 不得被自动重派");
        assertEquals(0, readAttemptTotal(SUB_FRESH), "未重派不得消耗预算");
        verify(taskDispatchPort, never()).assignNext(any(), eq(SUB_FRESH), any());
    }

    @Test
    @DisplayName("依赖未就绪的 BLOCKED 跳过（不误推依赖编排中的阻塞节点入调度链）")
    void blockedWithUnreadyDependencyIsSkipped() {
        seedSubTask(DEP_BLOCKING, "PENDING", STALE, "[]", null);
        seedSubTask(SUB_DEPENDENCY, "BLOCKED", STALE, "[" + DEP_BLOCKING + "]", null);

        autoRedispatchTask.scan();

        assertEquals("BLOCKED", readStatus(SUB_DEPENDENCY), "依赖未 DONE 时应保持 BLOCKED 等上游解锁");
        assertEquals(0, readAttemptTotal(SUB_DEPENDENCY), "跳过不得消耗预算");
        verify(taskDispatchPort, never()).assignNext(any(), eq(SUB_DEPENDENCY), any());
    }

    @Test
    @DisplayName("带人工介入标记的 BLOCKED 跳过（自动链已穷尽手段，等人工拍板）")
    void manualInterventionMarkedIsSkipped() {
        seedSubTask(SUB_MANUAL, "BLOCKED", STALE, "[]",
                "{\"manualIntervention\": {\"reason\": \"rework_limit_exceeded\"}}");

        autoRedispatchTask.scan();

        assertEquals("BLOCKED", readStatus(SUB_MANUAL), "人工介入标记在自动链中一律不碰");
        assertEquals(0, readAttemptTotal(SUB_MANUAL), "跳过不得消耗预算");
        verify(taskDispatchPort, never()).assignNext(any(), eq(SUB_MANUAL), any());
    }
}
