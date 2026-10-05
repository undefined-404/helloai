package com.helloai.it;

import com.helloai.common.constant.TaskMemberJoinSource;
import com.helloai.core.task.mapper.TaskAgentMemberMapper;
import com.helloai.core.task.service.TaskAgentMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task-Team 成员表（{@code task_agent_member}，V98）B 级真实库集成测试。
 *
 * <p><b>为什么必须补这条</b>：成员表的写入走自定义 {@code @Insert + ON CONFLICT}，
 * <b>未经 MyBatis-Plus 的 {@code ASSIGN_ID} 主键填充</b>，也不走 MP 的乐观锁 / 逻辑删除拦截；
 * 服务层单测（{@code TaskAgentMemberServiceImplTest}）只能验证「调了 mapper、参数对」，
 * 覆盖不了三类<b>只有真库才会暴露</b>的问题：</p>
 * <ol>
 *     <li>{@code uk(task_id, agent_id)} 幂等 upsert —— 重复登记不产生重复行、不抛
 *         {@code DuplicateKeyException}；</li>
 *     <li><b>并发登记同一 (taskId, agentId) 不污染事务</b>（PostgreSQL aborted 25P02 铁律）——
 *         若 upsert 写错成「先查后写」，并发下必撞唯一索引，整事务进入 aborted 后续全失败；</li>
 *     <li>{@code join_source} 的血统保留语义 —— 首次入队来源不被动摇，仅 REBUILT 可被真实入口覆盖。</li>
 * </ol>
 *
 * <p><b>真实边界</b>：PostgreSQL / Redis / RabbitMQ 均 Testcontainers（与
 * {@link AbstractItTestBase} 同口径），Flyway 按真实迁移脚本建表，SQL 全真实执行。</p>
 */
@DisplayName("Task-Team 成员表（V98）真实库集成验证")
class TaskAgentMemberPhaseBIT extends AbstractItTestBase {

    // 主键固定 9xxx 段 + it- 前缀，与内建 seed 隔离（AbstractItTestBase 口径）
    private static final long TASK_A = 9010L;
    private static final long TASK_B = 9011L;
    private static final long AGENT_A = 9210L;
    private static final long AGENT_B = 9211L;
    private static final long SUB_A = 9310L;

    @Autowired
    private TaskAgentMemberService taskAgentMemberService;

    @Autowired
    private TaskAgentMemberMapper mapper;

    /** P2-4：级联删除验证（task 域 / agent 域各自的级联入口）。 */
    @Autowired
    private com.helloai.core.task.service.TaskService taskService;

    @Autowired
    private com.helloai.core.agent.service.AgentService agentService;

    @BeforeEach
    void seed() {
        // 外键依赖：先清成员表，再清 task / sub_task / agent
        jdbcTemplate.update("DELETE FROM task_agent_member WHERE task_id IN (?, ?)", TASK_A, TASK_B);
        jdbcTemplate.update("DELETE FROM sub_task WHERE id IN (?)", SUB_A);
        jdbcTemplate.update("DELETE FROM task WHERE id IN (?, ?)", TASK_A, TASK_B);
        jdbcTemplate.update("DELETE FROM agent WHERE id IN (?, ?)", AGENT_A, AGENT_B);

        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-member-a', 'IN_PROGRESS', 'it', 'it')
                """, TASK_A);
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-member-b', 'IN_PROGRESS', 'it', 'it')
                """, TASK_B);
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, 'it-agent-a', 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, AGENT_A);
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, 'it-agent-b', 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, AGENT_B);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("幂等 upsert：重复 register 同一 (task,agent) 只有 1 行，不抛 DuplicateKey")
    void registerTwice_keepsSingleRow() {
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.ASSIGNED);
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.CLAIMED);

        assertEquals(1, countMembers(TASK_A), "重复登记同一成员仍只能有 1 行");
        assertTrue(taskAgentMemberService.isMember(TASK_A, AGENT_A), "登记后应为在队成员");
    }

    @Test
    @DisplayName("并发 upsert 同一 (task,agent)：不抛唯一约束冲突、不污染事务、仅 1 行")
    void concurrentRegister_keepsSingleRow_andNoAbort() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger();
        List<Throwable> firstError = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.ASSIGNED);
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                    firstError.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发登记未在 60s 内结束");

        if (errorCount.get() > 0) {
            Throwable t = firstError.get(0);
            fail("并发登记抛异常（" + errorCount.get() + "/" + threads + " 失败）："
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        assertEquals(1, countMembers(TASK_A),
                "并发登记同一成员应仍只有 1 行（ON CONFLICT 原子 upsert，不产生 duplicate）");
    }

    @Test
    @DisplayName("join_source 血统保留：首次 ASSIGNED 不被后续 CLAIMED/REBUILT 覆盖")
    void joinSource_keepsFirstNonRebuilt() {
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.ASSIGNED);
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.CLAIMED);
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.REBUILT);

        assertEquals("ASSIGNED", joinSourceOf(TASK_A, AGENT_A),
                "首次入队来源 ASSIGNED 不应被后续 CLAIMED / REBUILT 覆盖");
    }

    @Test
    @DisplayName("join_source 升级：仅当原值为 REBUILT 时才被真实入口覆盖")
    void joinSource_upgradesFromRebuiltOnly() {
        // 先经重建对账写入 REBUILT（无真实入口可观测）
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.REBUILT);
        assertEquals("REBUILT", joinSourceOf(TASK_A, AGENT_A));

        // 随后真实入口（认领）到来，应把 REBUILT 升级为真实来源
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.CLAIMED);
        assertEquals("CLAIMED", joinSourceOf(TASK_A, AGENT_A),
                "原值为 REBUILT 时应被真实入口（CLAIMED）升级覆盖");
    }

    @Test
    @DisplayName("重建对账：从 sub_task.assigned_agent_id 补齐成员表（跨任务多 agent）")
    void rebuild_deducesMembersFromSubTaskAssignments() {
        // 权威源：TASK_A 的 SUB_A 指派给 AGENT_B（成员表当前为空）
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, 'it-sub-a', 'ASSIGNED', ?, 'it', 'it')
                """, SUB_A, TASK_A, AGENT_B);

        int written = taskAgentMemberService.rebuildFromAuthoritativeAssignments();

        assertTrue(written >= 1, "应至少写入 1 行（实际=" + written + "）");
        assertTrue(taskAgentMemberService.isMember(TASK_A, AGENT_B),
                "重建后 AGENT_B 应成为 TASK_A 的成员");
    }

    @Test
    @DisplayName("权威源兜底：成员表为空但仍是当前执行者时，isCurrentExecutorOfTask 命中")
    void deriveOnMiss_currentExecutorCountsAsMember() {
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, 'it-sub-a', 'ASSIGNED', ?, 'it', 'it')
                """, SUB_A, TASK_A, AGENT_A);

        assertFalse(taskAgentMemberService.isMember(TASK_A, AGENT_A),
                "成员表为空，isMember 应为 false");
        assertTrue(taskAgentMemberService.isCurrentExecutorOfTask(TASK_A, AGENT_A),
                "但权威源显示其为当前执行者，兜底判定应为 true");
    }

    @Test
    @DisplayName("边界：agent 不是该任务成员，也不是当前执行者 -> 两源均 false")
    void outsiderRejectedByBothSources() {
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.ASSIGNED);

        assertFalse(taskAgentMemberService.isMember(TASK_A, AGENT_B),
                "AGENT_B 未登记，isMember 应 false");
        assertFalse(taskAgentMemberService.isCurrentExecutorOfTask(TASK_A, AGENT_B),
                "AGENT_B 无子任务指派，兜底也应 false");
    }

    // ==================== P2-4：级联删除（2026-10-05） ====================

    @Test
    @DisplayName("P2-4 级联删除 task：5 张子表齐备时 deleteTaskCascade 不再撞 FK（此前 500）")
    void deleteTaskCascade_clearsAllReferencingChildTables() {
        // 构造「跑过执行 / 被指派过成员」的 task：引用 task.id 的 5 张子表各插 1 行
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, 'it-sub-a', 'ASSIGNED', ?, 'it', 'it')
                """, SUB_A, TASK_A, AGENT_A);
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.ASSIGNED);
        jdbcTemplate.update("""
                INSERT INTO task_running_spec (id, task_id, create_by, update_by)
                VALUES (?, ?, 'it', 'it')
                """, 9411L, TASK_A);
        jdbcTemplate.update("""
                INSERT INTO task_execution_record (id, task_id, sub_task_id, create_by, update_by)
                VALUES (?, ?, ?, 'it', 'it')
                """, 9412L, TASK_A, SUB_A);

        // 修复前：漏清 task_running_spec / task_execution_record / task_agent_member →
        // 物理删除 task 行时撞 *_task_id_fkey → HTTP 500。修复后应整体成功。
        taskService.deleteTaskCascade(TASK_A, "it-member-a");

        assertFalse(rowExists("task", TASK_A), "task 行应被物理删除");
        assertEquals(0, childRows("module", "task_id", TASK_A), "module 子表应清空");
        assertEquals(0, childRows("sub_task", "task_id", TASK_A), "sub_task 子表应清空");
        assertEquals(0, childRows("task_running_spec", "task_id", TASK_A), "task_running_spec 子表应清空");
        assertEquals(0, childRows("task_execution_record", "task_id", TASK_A), "task_execution_record 子表应清空");
        assertEquals(0, childRows("task_agent_member", "task_id", TASK_A), "task_agent_member 子表应清空");
    }

    @Test
    @DisplayName("P2-4 级联删除 agent：清掉 task_agent_member 成员行后 DELETE agent 不再撞 FK（此前 500）")
    void deleteAgentCascade_clearsTaskAgentMember() {
        // 构造「被指派为任务成员」的 agent：task_agent_member 有 FK task_agent_member_agent_id_fkey
        taskAgentMemberService.register(TASK_A, AGENT_A, TaskMemberJoinSource.ASSIGNED);
        assertEquals(1, childRows("task_agent_member", "agent_id", AGENT_A), "前置：成员行存在");

        // 修复前：未清 task_agent_member → 删除 agent 行时撞 FK → HTTP 500。修复后应整体成功。
        agentService.deleteAgentCascade(AGENT_A, "it-agent-a");

        assertFalse(rowExists("agent", AGENT_A), "agent 行应被物理删除");
        assertEquals(0, childRows("task_agent_member", "agent_id", AGENT_A), "成员行应被清空");
    }

    // ==================== 断言辅助 ====================

    private int countMembers(long taskId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_agent_member WHERE task_id = ? AND deleted = 0 AND status = 'ACTIVE'",
                Integer.class, taskId);
        return n == null ? 0 : n;
    }

    private String joinSourceOf(long taskId, long agentId) {
        return jdbcTemplate.queryForObject(
                "SELECT join_source FROM task_agent_member WHERE task_id = ? AND agent_id = ?",
                String.class, taskId, agentId);
    }

    /** 指定 id 的行在表中是否存在（表名由本测试内部常量给出，非外部输入，无注入面）。 */
    private boolean rowExists(String table, long id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /** 子表按外键列计数（用于验证级联删除清空）。 */
    private int childRows(String table, String column, long value) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return n == null ? 0 : n;
    }
}
