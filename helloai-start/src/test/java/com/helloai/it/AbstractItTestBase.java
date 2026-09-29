package com.helloai.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * B 级集成测试基类（2026-09-29 引入，审计建议 #5）。
 *
 * <p><b>基础设施</b>：</p>
 * <ul>
 *   <li>{@code @SpringBootTest(webEnvironment = MOCK)} + 测试专用启动类
 *       {@link ItTestApplication}（无 {@code @EnableScheduling}，调度由测试手动触发）；
 *       <b>MOCK 而非 NONE</b>：生产装配含构造器注入 {@code HttpServletRequest} 的控制器
 *       （SubTaskController/CredentialController 等），NONE 无 MockServletContext 导致
 *       装配失败（2026-09-29 本机 Docker 实跑暴露）；MOCK 同样不监听端口，语义不变。</li>
 *   <li>{@code @DynamicPropertySource} 把 PG / Redis / RabbitMQ 容器端点注入运行时，
 *       与 {@code application-it.yml}（{@code @ActiveProfiles("it")}）合并成完整配置；</li>
 *   <li>{@code JdbcTemplate} 供 seed / 断言使用（真实 JDBC，验证落库事实）。</li>
 * </ul>
 *
 * <p><b>测试数据口径</b>：前置数据用 {@code JdbcTemplate} 直插（绕过业务层），
 * 主键固定 9xxx 段、带 {@code it-} 前缀，与 V1 内建 seed（均为小 id / 业务语义 id）隔离；
 * 每个用例自建独立 eventId / 子任务，互不共享状态，类间无依赖。</p>
 *
 * <p><b>等待语义</b>：MQ 消费 / Outbox confirm 是真实异步链路，断言前用
 * {@link #awaitUntil} 轮询（100ms 步进，超时 fail 并带描述），
 * 不允许裸 {@code Thread.sleep} 定长等待（协作规约 §25：验证可重复、可解释）。</p>
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, classes = ItTestApplication.class)
public abstract class AbstractItTestBase {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ItContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", ItContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", ItContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", ItContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> ItContainers.REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", ItContainers.RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> ItContainers.RABBITMQ.getMappedPort(5672));
    }

    // ==================== 前置数据 seed（JdbcTemplate 直插，主键 9xxx 段） ====================

    /**
     * 种子：task + agent（EXECUTOR/ACTIVE/CLI_CLIENT）+ sub_task（ASSIGNED 且已分配该 agent）。
     *
     * <p>{@code task} 状态 IN_PROGRESS、{@code sub_task} 状态 ASSIGNED 对齐
     * 「已分配待执行」的最小业务态；{@code agent.access_type} 非空是
     * {@code ExecutionCommandServiceImpl.createAssignedCommand} 的前置校验。</p>
     */
    protected void seedAssignedSubTask(long taskId, long agentId, long subTaskId) {
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, ?, 'IN_PROGRESS', 'it', 'it')
                """, taskId, "it-task-" + taskId);
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, ?, 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, agentId, "it-agent-" + agentId);
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, ?, 'ASSIGNED', ?, 'it', 'it')
                """, subTaskId, taskId, "it-subtask-" + subTaskId, agentId);
    }

    /**
     * 种子：PENDING 执行记录（status=PENDING + version=0 是 markRunning CAS 的前置态）。
     *
     * @return 执行记录 id
     */
    protected long seedPendingExecutionRecord(long recordId, String eventId, long subTaskId, long agentId) {
        jdbcTemplate.update("""
                INSERT INTO agent_execution_record
                    (id, event_id, sub_task_id, agent_id, status, worker_node, retry_count,
                     trigger_type, access_type, create_by, update_by)
                VALUES (?, ?, ?, ?, 'PENDING', 'it-node', 0, 'assigned', 'CLI_CLIENT', 'it', 'it')
                """, recordId, eventId, subTaskId, agentId);
        return recordId;
    }

    // ==================== 断言辅助 ====================

    /**
     * 轮询等待真实异步链路（MQ 消费 / Outbox confirm）达到期望状态。
     *
     * <p>100ms 步进、默认 15s 超时；超时以 {@code fail} 终止并携带描述，
     * 使失败原因（等的是哪一步）可读可查。禁止裸 sleep 定长等待。</p>
     */
    protected static void awaitUntil(String description, long timeoutSeconds,
                                     java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断: " + description, e);
            }
        }
        fail("等待超时(" + timeoutSeconds + "s): " + description);
    }

    /** 查询某列是否存在（information_schema，验证迁移落库事实）。 */
    protected boolean columnExists(String table, String column) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = ?
                """, Integer.class, table, column);
        return n != null && n > 0;
    }

    /** 查询某表是否存在。 */
    protected boolean tableExists(String table) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_name = ?
                """, Integer.class, table);
        return n != null && n > 0;
    }
}