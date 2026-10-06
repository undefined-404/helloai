package com.helloai.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 级联删除「FK 删序」真实库集成测试（D-1，2026-10-05）。
 *
 * <p><b>为什么必须补这条</b>：{@code ReviewPortAdapter} 的单测（{@code ReviewPortAdapterTest}）
 * 是 <b>mock</b> —— 只断言「调了哪个 mapper、调用顺序」，<b>结构上捕不到真实外键约束</b>。
 * 而 {@code review_recheck_log} 有两处 FK：</p>
 * <ul>
 *   <li>{@code review_recheck_log.review_record_id → review_record(id)}（review_record 是父）</li>
 *   <li>{@code review_recheck_log.sub_task_id → sub_task(id)}</li>
 * </ul>
 * <p>若先删 review_record（父）再删 review_recheck_log（子），真实库会抛
 * {@code ... violates foreign key constraint "review_recheck_log_review_record_id_fkey"} → HTTP 500。
 * 本 IT 用真实 PG（Testcontainers + Flyway 建表）复现该链路，验证「子先父后」修复后不再撞 FK、
 * 且 D-1 补齐的全部漏删表归零。</p>
 *
 * <p><b>真实边界</b>：PG / Redis / RabbitMQ 均 Testcontainers（与 {@link AbstractItTestBase} 同口径），
 * Flyway 按真实迁移脚本建表，SQL 全真实执行。</p>
 */
@DisplayName("级联删除 FK 删序（D-1）真实库集成验证")
class CascadeDeleteFkOrderIT extends AbstractItTestBase {

    // 主键固定 9xxx 段 + it- 前缀，与内建 seed 隔离（AbstractItTestBase 口径）。
    // 2026-10-06：由 9101 段迁至 9501 段 —— 9101 段已被 MqExecutionCommandConsumerIT 占用，
    // 二者共用同段时（Testcontainers 容器为跨类静态单例）本类残留会撞其后跑者的 agent_pkey。
    private static final long TASK = 9501L;
    private static final long MODULE = 9501L;
    private static final long SUB = 9501L;
    private static final long AGENT = 9501L;
    private static final long RECORD = 9501L;
    private static final long RECHECK = 9501L;
    private static final long EVENT = 9501L;
    private static final long SESSION = 9501L;
    private static final long REWARD = 9501L;
    private static final long ITERATION = 9501L;
    private static final long PROFILE = 9501L;
    private static final long TEAM_MEMBER = 9501L;
    private static final long TIMELINE = 9501L;
    private static final long TEAM = 9501L;

    @Autowired
    private com.helloai.core.task.service.TaskService taskService;
    @Autowired
    private com.helloai.core.agent.service.AgentService agentService;

    /** 每个用例独立、可重复：先尽量清残留（子先父后），再 seed。 */
    @BeforeEach
    void seed() {
        clearResidual();

        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-cascade-task', 'IN_PROGRESS', 'it', 'it')
                """, TASK);
        jdbcTemplate.update("""
                INSERT INTO module (id, task_id, name, create_by, update_by)
                VALUES (?, ?, 'it-mod', 'it', 'it')
                """, MODULE, TASK);
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, 'it-cascade-agent', 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, AGENT);
        // sub_task.module_id → module(id)：sub_task 必须先于 module 删除（本 seed 一并覆盖该边）
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, module_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, ?, 'it-cascade-sub', 'REVIEW', ?, 'it', 'it')
                """, SUB, TASK, MODULE, AGENT);
        // ★关键种子：review_record（父） + review_recheck_log（子，FK→review_record）
        jdbcTemplate.update("""
                INSERT INTO review_record (id, sub_task_id, reviewer_agent_id, result, score, round, create_by, update_by)
                VALUES (?, ?, ?, 'APPROVED', 4, 1, 'it', 'it')
                """, RECORD, SUB, AGENT);
        jdbcTemplate.update("""
                INSERT INTO review_recheck_log
                    (id, review_record_id, sub_task_id, original_result, recheck_result,
                     discrepancy, reviewer_agent, score, create_by, update_by)
                VALUES (?, ?, ?, 'APPROVED', 'REJECTED', 1, ?, 2, 'it', 'it')
                """, RECHECK, RECORD, SUB, AGENT);
        // D-1 其余漏删表（无 FK 语义列）
        jdbcTemplate.update("""
                INSERT INTO agent_event (id, event_id, run_id, task_id, sub_task_id, agent_id, event_type)
                VALUES (?, 'it-ev', 'it-run', ?, ?, ?, 'agent_started')
                """, EVENT, TASK, SUB, AGENT);
        jdbcTemplate.update("""
                INSERT INTO agent_session (id, run_id, task_id, sub_task_id, agent_id, status)
                VALUES (?, 'it-run', ?, ?, ?, 'ACTIVE')
                """, SESSION, TASK, SUB, AGENT);
        jdbcTemplate.update("""
                INSERT INTO reward_log (id, agent_id, sub_task_id, reason, delta, balance)
                VALUES (?, ?, ?, 'it-reward', 1, 1)
                """, REWARD, AGENT, SUB);
        jdbcTemplate.update("""
                INSERT INTO task_iteration (id, task_id, task_name)
                VALUES (?, ?, 'it-iter')
                """, ITERATION, TASK);
        jdbcTemplate.update("""
                INSERT INTO agent_quality_profile (id, agent_id)
                VALUES (?, ?)
                """, PROFILE, AGENT);
        jdbcTemplate.update("""
                INSERT INTO team_member (id, team_id, agent_id, slot_role)
                VALUES (?, ?, ?, 'EXECUTOR')
                """, TEAM_MEMBER, TEAM, AGENT);
        jdbcTemplate.update("""
                INSERT INTO task_timeline (id, task_id, sub_task_id, event_type, role)
                VALUES (?, ?, ?, 'it_timeline', 'EXECUTOR')
                """, TIMELINE, TASK, SUB);
    }

    @Test
    @DisplayName("★task 级联：子先父后（review_recheck_log 先于 review_record）→ 不撞 FK，全表归零")
    void deleteTaskCascade_childBeforeParent_noFkViolation() {
        // 前置：子表确有引用行
        assertEquals(1, childRows("review_recheck_log", "sub_task_id", SUB), "前置：抽检日志存在");
        assertEquals(1, childRows("review_record", "sub_task_id", SUB), "前置：审查记录存在");

        // 修复前：先删 review_record（父）→ 撞 review_recheck_log_review_record_id_fkey → 抛异常（HTTP 500）
        // 修复后：先 review_recheck_log（子）→ 再 review_record（父）→ 整体成功
        assertDoesNotThrow(() -> taskService.deleteTaskCascade(TASK, "it-cascade-task"),
                "级联删除不应抛 FK 异常（子先父后已修复）");

        assertFalse(rowExists("task", TASK), "task 行应被物理删除");
        assertEquals(0, childRows("review_recheck_log", "sub_task_id", SUB), "review_recheck_log 应清空");
        assertEquals(0, childRows("review_record", "sub_task_id", SUB), "review_record 应清空");
        assertEquals(0, childRows("sub_task", "task_id", TASK), "sub_task 应清空");
        assertEquals(0, childRows("module", "task_id", TASK), "module 应清空");
        assertEquals(0, childRows("agent_event", "task_id", TASK), "agent_event 应清空（D-1）");
        assertEquals(0, childRows("agent_session", "task_id", TASK), "agent_session 应清空（D-1）");
        assertEquals(0, childRows("reward_log", "sub_task_id", SUB), "reward_log 应清空（D-1）");
        assertEquals(0, childRows("task_iteration", "task_id", TASK), "task_iteration 应清空（D-1）");
        assertEquals(0, childRows("task_timeline", "task_id", TASK), "task_timeline 应清空");
    }

    @Test
    @DisplayName("agent 级联：清 agent_event/agent_session/agent_quality_profile/team_member → 不撞 FK，全表归零")
    void deleteAgentCascade_clearsLeakyAgentTables() {
        assertEquals(1, childRows("agent_event", "agent_id", AGENT), "前置：事件存在");
        assertEquals(1, childRows("team_member", "agent_id", AGENT), "前置：团队花名册成员存在");

        assertDoesNotThrow(() -> agentService.deleteAgentCascade(AGENT, "it-cascade-agent"),
                "Agent 级联删除不应抛 FK 异常");

        assertFalse(rowExists("agent", AGENT), "agent 行应被物理删除");
        assertEquals(0, childRows("agent_event", "agent_id", AGENT), "agent_event 应清空（D-1）");
        assertEquals(0, childRows("agent_session", "agent_id", AGENT), "agent_session 应清空（D-1）");
        assertEquals(0, childRows("agent_quality_profile", "agent_id", AGENT), "agent_quality_profile 应清空（D-1）");
        assertEquals(0, childRows("team_member", "agent_id", AGENT), "team_member 应清空（D-1 收尾）");
    }

    // ==================== 清理 ====================

    /**
     * 用例后清场：本类 seed 的 9501 段数据必须回收，否则残留行会污染外层共享容器
     * （{@link ItContainers} 跨类静态单例）中其它 IT 类 —— 2026-10-06 实跑暴露：
     * 本类原用 9101 段且<b>无 {@code @AfterEach}</b>，残留 {@code agent(id=9101)}
     * 使后跑的 {@code MqExecutionCommandConsumerIT}（同用 9101 段）seed 时
     * 撞 {@code agent_pkey}（{@code DuplicateKeyException}），门禁 5（{@code -Dtest='*IT'}）确定性失败。
     *
     * <p>与 {@link #seed()} 共用 {@link #clearResidual()} 单一口径，避免两处清理漂移。</p>
     */
    @AfterEach
    void cleanup() {
        clearResidual();
    }

    /**
     * 按 FK 依赖顺序（子 → 父）清除本类 9501 段全部 seed 残留。
     * {@code @BeforeEach}（保证用例可重复）与 {@code @AfterEach}（防类间污染）共用。
     */
    private void clearResidual() {
        // 子 → 父：review_recheck_log 引用 review_record / sub_task，故最先删
        jdbcTemplate.update("DELETE FROM review_recheck_log WHERE id = ?", RECHECK);
        jdbcTemplate.update("DELETE FROM review_record WHERE id = ?", RECORD);
        jdbcTemplate.update("DELETE FROM agent_event WHERE id = ?", EVENT);
        jdbcTemplate.update("DELETE FROM agent_session WHERE id = ?", SESSION);
        jdbcTemplate.update("DELETE FROM reward_log WHERE id = ?", REWARD);
        jdbcTemplate.update("DELETE FROM task_iteration WHERE id = ?", ITERATION);
        jdbcTemplate.update("DELETE FROM agent_quality_profile WHERE id = ?", PROFILE);
        jdbcTemplate.update("DELETE FROM team_member WHERE id = ?", TEAM_MEMBER);
        jdbcTemplate.update("DELETE FROM task_timeline WHERE id = ?", TIMELINE);
        jdbcTemplate.update("DELETE FROM task_agent_member WHERE task_id = ?", TASK);
        // sub_task 引用 module（module_id）与 agent（assigned_agent_id）：先 sub_task 再 module
        jdbcTemplate.update("DELETE FROM sub_task WHERE id = ?", SUB);
        jdbcTemplate.update("DELETE FROM module WHERE id = ?", MODULE);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK);
        jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT);
    }

    // ==================== 断言辅助 ====================

    private boolean rowExists(String table, long id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    private int childRows(String table, String column, long value) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return n == null ? 0 : n;
    }
}
