package com.helloai.it;

import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.TaskBaseline;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase B（TaskRunningSpec 独立表）B 级集成测试（2026-10-02）。
 *
 * <p><b>为什么必须补这条</b>：{@code TaskRunningSpecServiceImpl} 共 255 行，
 * 双轨二选一前只有 4 例**纯 mock 单测**，从未在真实数据库上跑过；B4' 把它变成
 * 唯一实现后，它就是全链路 Running Spec 的唯一落库路径。mock 单测无法覆盖
 * JSONB 列读写、{@code (task_id, sub_task_id)} 唯一索引 upsert、
 * ContextSummary 编译回写这三类<b>只有真库才会暴露</b>的问题。</p>
 *
 * <p><b>验证目标（三条硬断言）</b>：</p>
 * <ol>
 *     <li>执行回填<b>只写独立表</b>，且<b>不再</b>回写 {@code task.context.runningSpec}
 *         —— 这是 Phase B 是否真正落地的判据（用例 1）；</li>
 *     <li>同一 {@code (taskId, subTaskId)} 重复回填（rework）<b>只有 1 行</b>且为最新
 *         —— 唯一索引 upsert 语义（用例 2）；</li>
 *     <li>并发回填同一 {@code (taskId, subTaskId)} 不产生重复行、不抛唯一约束冲突
 *         —— 多实例部署前提（用例 6）。</li>
 * </ol>
 *
 * <p>真实边界：PostgreSQL / Redis / RabbitMQ 均为 Testcontainers 容器（与
 * {@link AbstractItTestBase} 同口径），Flyway 按真实迁移脚本建表，SQL 全部真实执行。</p>
 */
@DisplayName("TaskRunningSpec Phase B（独立表）真实库集成验证")
class TaskRunningSpecPhaseBIT extends AbstractItTestBase {

    // 主键固定 9xxx 段 + it- 前缀，与内建 seed 隔离（AbstractItTestBase 口径）。
    // 2026-10-05：ID 段切到 9401+，与同容器共享的其它 IT 类错开——
    //   MqExecutionCommandConsumerIT 用 9101（task/agent/sub_task/record 四合一），
    //   AgentCommandOutboxIT 用 9201，AgentExecutionRecordCasIT 用 9301/9302，
    //   TaskAgentMemberPhaseBIT 用 9010/9011/9210/9211/9310。
    //   此前 SUB_A=9101 与 MqExecutionCommandConsumerIT 撞号，且后者 seed 的
    //   agent_execution_record(sub_task_id=9101) 无清理，跨类污染导致本类
    //   `DELETE FROM sub_task WHERE id=9101` 撞 agent_execution_record_sub_task_id_fkey（setup ERROR）。
    private static final long TASK_A = 9401L;
    private static final long TASK_B = 9402L;
    private static final long SUB_A = 9403L;
    private static final long SUB_B = 9404L;
    private static final long SUB_C = 9405L;
    private static final long AGENT_ID = 9406L;

    @Autowired
    private TaskRunningSpecPort taskRunningSpecPort;

    @Autowired
    private TaskRunningSpecService taskRunningSpecService;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM task_execution_record WHERE task_id IN (?, ?)", TASK_A, TASK_B);
        jdbcTemplate.update("DELETE FROM task_running_spec WHERE task_id IN (?, ?)", TASK_A, TASK_B);
        // 先清引用 sub_task 的子表，避免残留行触发 FK 冲突（agent_execution_record 等）：
        // 容器为跨类静态单例，任何 IT 残留的同 id 执行记录都会让 DELETE sub_task 报
        // agent_execution_record_sub_task_id_fkey（2026-10-05 L2 门禁实跑暴露）。
        jdbcTemplate.update("DELETE FROM agent_execution_record WHERE sub_task_id IN (?, ?, ?)", SUB_A, SUB_B, SUB_C);
        jdbcTemplate.update("DELETE FROM sub_task WHERE id IN (?, ?, ?)", SUB_A, SUB_B, SUB_C);
        jdbcTemplate.update("DELETE FROM task WHERE id IN (?, ?)", TASK_A, TASK_B);

        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-spec-a', 'IN_PROGRESS', 'it', 'it')
                """, TASK_A);
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-spec-b', 'IN_PROGRESS', 'it', 'it')
                """, TASK_B);
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, create_by, update_by)
                VALUES (?, ?, 'it-sub-a', 'ASSIGNED', 'it', 'it')
                """, SUB_A, TASK_A);
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, create_by, update_by)
                VALUES (?, ?, 'it-sub-b', 'ASSIGNED', 'it', 'it')
                """, SUB_B, TASK_A);
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, create_by, update_by)
                VALUES (?, ?, 'it-sub-c', 'ASSIGNED', 'it', 'it')
                """, SUB_C, TASK_B);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("执行回填只写独立表，不再写回 task.context.runningSpec（Phase B 落地判据）")
    void appendExecutionRecord_writesTableOnly_notJsonb() {
        taskRunningSpecPort.parseAndAppendExecutionRecord(
                TASK_A, SUB_A, "子任务A", AGENT_ID, rawRecord("实现了登录接口"));

        assertEquals(1, countRecords(TASK_A), "task_execution_record 应落 1 行");
        assertEquals("实现了登录接口", summaryOf(TASK_A, SUB_A));
        assertNotNull(contextSummaryOf(TASK_A), "回填后应重算 ContextSummary");
        assertTrue(contextSummaryOf(TASK_A).contains("已完成 1 个子任务"),
                "ContextSummary 应由真实记录编译: 实际=" + contextSummaryOf(TASK_A));
        assertEquals(0, countJsonbRunningSpec(TASK_A),
                "Phase B 后不得再写 task.context.runningSpec（否则等于双轨未切换）");
        assertEquals("实现了登录接口", taskRunningSpecPort.findExecutionSummary(TASK_A, SUB_A),
                "端口读路径应能取回刚写入的摘要");
    }

    @Test
    @DisplayName("同一子任务返工重复回填：只有 1 行且为最新版本（唯一索引 upsert）")
    void reworkSameSubTask_replacesRecord_singleRow() {
        taskRunningSpecPort.parseAndAppendExecutionRecord(
                TASK_A, SUB_A, "子任务A", AGENT_ID, rawRecord("第一版"));
        taskRunningSpecPort.parseAndAppendExecutionRecord(
                TASK_A, SUB_A, "子任务A", AGENT_ID, rawRecord("第二版返工"));

        assertEquals(1, countRecords(TASK_A), "rework 后同一子任务仍只能有 1 行");
        assertEquals("第二版返工", summaryOf(TASK_A, SUB_A), "upsert 应保留最新一份");
    }

    @Test
    @DisplayName("解析失败且输出非空白：fallback 前 200 字符 + ... 仍落库")
    void unparsableOutput_fallsBackToTruncatedSummary() {
        String longOutput = "x".repeat(500);

        taskRunningSpecPort.parseAndAppendExecutionRecord(
                TASK_A, SUB_B, "子任务B", AGENT_ID, longOutput);

        assertEquals(1, countRecords(TASK_A));
        String summary = summaryOf(TASK_A, SUB_B);
        assertTrue(summary.startsWith("x".repeat(200)) && summary.endsWith("..."),
                "fallback 应为前 200 字符 + 省略号: 实际长度=" + summary.length());
    }

    @Test
    @DisplayName("输出为空白：不落任何记录")
    void blankOutput_writesNothing() {
        taskRunningSpecPort.parseAndAppendExecutionRecord(TASK_B, SUB_C, "子任务C", AGENT_ID, "   ");

        assertEquals(0, countRecords(TASK_B), "空白输出不应产生记录");
    }

    @Test
    @DisplayName("Baseline 初始化落 JSONB 列，并驱动 Prompt 全局上下文段")
    void baselineInitialize_persistsJsonbAndRendersPromptSection() {
        taskRunningSpecService.initialize(TASK_B,
                TaskBaseline.builder().goal("做一个登录模块").constraints("Java 17 / Spring Boot 3").build());

        assertEquals(1, countSpecs(TASK_B), "task_running_spec 应落 1 行");
        assertEquals("做一个登录模块",
                jdbcTemplate.queryForObject(
                        "SELECT baseline->>'goal' FROM task_running_spec WHERE task_id = ?",
                        String.class, TASK_B),
                "baseline 应以 JSONB 真实落库（不是整行文本覆盖）");

        String section = taskRunningSpecPort.buildExecutorPromptSection(TASK_B);
        assertTrue(section.contains("## 任务全局上下文"), "Prompt 段应有标题");
        assertTrue(section.contains("做一个登录模块"), "Prompt 段应含总体目标");
        assertTrue(section.contains("Java 17 / Spring Boot 3"), "Prompt 段应含平台约束");
    }

    @Test
    @DisplayName("并发回填同一 (taskId, subTaskId)：不产生重复行、不抛唯一约束冲突")
    void concurrentAppendSameSubTask_keepsSingleRow() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errorCount = new AtomicInteger();
        List<Throwable> firstError = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    taskRunningSpecPort.parseAndAppendExecutionRecord(
                            TASK_A, SUB_A, "子任务A", AGENT_ID, rawRecord("并发版本-" + idx));
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                    firstError.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(90, TimeUnit.SECONDS), "并发回填未在 90s 内结束");

        if (errorCount.get() > 0) {
            Throwable t = firstError.get(0);
            fail("并发回填抛异常（" + errorCount + "/" + threads + " 失败）："
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        assertEquals(1, countRecords(TASK_A),
                "并发回填同一子任务后仍应只有 1 行（不得撞 (task_id, sub_task_id) 唯一索引）");
    }

    // ==================== 断言辅助（真实 JDBC，验证落库事实） ====================

    /** 构造带 EXECUTION_RECORD 块的 executor 原始输出。 */
    private static String rawRecord(String summary) {
        return "产出正文前置说明\n\n## EXECUTION_RECORD\n"
                + "SUMMARY: " + summary + "\n"
                + "KEY_DECISIONS:\n- 采用 JWT 无状态鉴权\n"
                + "DOWNSTREAM_NOTES:\n- 下游需按 user_id 透传\n"
                + "DELIVERABLES:\n- LoginController.java\n";
    }

    private int countRecords(long taskId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution_record WHERE task_id = ?", Integer.class, taskId);
        return n == null ? 0 : n;
    }

    private int countSpecs(long taskId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_running_spec WHERE task_id = ?", Integer.class, taskId);
        return n == null ? 0 : n;
    }

    private String summaryOf(long taskId, long subTaskId) {
        return jdbcTemplate.queryForObject(
                "SELECT summary FROM task_execution_record WHERE task_id = ? AND sub_task_id = ?",
                String.class, taskId, subTaskId);
    }

    private String contextSummaryOf(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT context_summary FROM task_running_spec WHERE task_id = ?",
                String.class, taskId);
    }

    /** 统计仍在用 JSONB 存 runningSpec 的任务数（Phase B 后必须为 0）。 */
    private int countJsonbRunningSpec(long taskId) {
        // 注意：不能写 `context ? 'runningSpec'` —— JDBC 会把 ? 解析为参数占位符
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task WHERE id = ? AND context -> 'runningSpec' IS NOT NULL",
                Integer.class, taskId);
        return n == null ? 0 : n;
    }
}
