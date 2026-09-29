package com.helloai.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1：Flyway 迁移全量 apply（审计建议 #5-1）。
 *
 * <p><b>被测事实</b>：helloai-start 是唯一携带 85 个 Flyway 迁移脚本的模块
 * （V1、V2、V13~V95，V3~V12 为历史缺号，见差距表 C-6）。B 级集成在空 PG 容器上
 * 从零执行完整迁移链，一次性暴露「只在新库上才炸」的脚本缺陷
 * （缺列 / 缺索引 / CHECK 约束冲突 / 幂等性缺失，CODE_STYLE §16）。</p>
 *
 * <p><b>断言口径</b>：</p>
 * <ul>
 *   <li>迁移文件数 == {@code flyway_schema_history} 成功记录数（沿着 classpath 动态比对，
 *       新增迁移自动纳入，不硬编码总数）；</li>
 *   <li>跨迁移链的最终 schema 抽查：乐观锁 version（V62）、poller 触及
 *       last_attempt_time（V16+V23 重命名）、outbox confirms 三件套（V19/V20/V23）、
 *       幂等日志表（V18）—— 验证「增量脚本 → 最终库形态」的累积正确性；</li>
 *   <li>版本缺号是已注册历史事实（V3~V12 不存在于 classpath），本测试不要求版本连续，
 *       只要求「凡在 classpath 的脚本必已 apply」。</li>
 * </ul>
 */
@DisplayName("B1: Flyway 迁移全量 apply")
class FlywayMigrationIT extends AbstractItTestBase {

    @Test
    @DisplayName("classpath 内每个 V* 迁移脚本都已成功应用且无失败记录")
    void allClasspathMigrationsApplied() throws IOException {
        Resource[] scripts = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql");
        assertTrue(scripts.length > 0, "classpath 上应存在 Flyway 迁移脚本");

        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE",
                Integer.class);

        assertEquals(scripts.length, applied,
                "classpath 迁移文件数应等于成功 apply 数（新增迁移自动纳入比对）");
        assertEquals(0, failed, "不允许存在失败的迁移记录");
    }

    @Test
    @DisplayName("跨迁移链最终 schema 形态抽查：乐观锁 / poller / outbox confirms / 幂等日志")
    void finalSchemaShapeIncludesIncrementalColumns() {
        // V16 补 poller 字段 + V23 字段命名规范化（trigger → trigger_type, last_attempt_at → last_attempt_time）
        assertTrue(columnExists("agent_execution_record", "trigger_type"),
                "V16+V23: agent_execution_record.trigger_type 应存在");
        assertTrue(columnExists("agent_execution_record", "agent_id"),
                "V16: agent_execution_record.agent_id 应存在");
        assertTrue(columnExists("agent_execution_record", "last_attempt_time"),
                "V16+V23: agent_execution_record.last_attempt_time 应存在");
        // V62：@Version 乐观锁列，状态机 CAS 双条件之一
        assertTrue(columnExists("agent_execution_record", "version"),
                "V62: agent_execution_record.version 应存在");
        // V19/V20 + V23：Outbox 主表与 confirms 三件套（confirmed_at → confirmed_time）
        assertTrue(tableExists("agent_command_outbox"), "V19: agent_command_outbox 表应存在");
        assertTrue(columnExists("agent_command_outbox", "confirmed_time"),
                "V20+V23: agent_command_outbox.confirmed_time 应存在");
        assertTrue(columnExists("agent_command_outbox", "last_sent_time"),
                "V20+V23: agent_command_outbox.last_sent_time 应存在");
        // V18：幂等消费 DB 兜底日志表（唯一索引 uk_event_consumption_log_msg_consumer）
        assertTrue(tableExists("event_consumption_log"), "V18: event_consumption_log 表应存在");
        // V65：Agent 事件表（TASK_ASSIGNED 埋点依赖）
        assertTrue(tableExists("agent_event"), "V65: agent_event 表应存在");
    }

    @Test
    @DisplayName("版本缺号为历史事实：V1/V2 与 V13 之后的版本均在，V13 之前不存在 V3~V12")
    void migrationVersionGapIsRegisteredHistory() {
        assertEquals(1, countAppliedVersion("1"), "V1 必须已应用");
        assertEquals(1, countAppliedVersion("2"), "V2 必须已应用");
        assertEquals(1, countAppliedVersion("13"), "V13 必须已应用（缺号后的首个版本）");
        // V3~V12 不在 classpath（历史合并/清理，差距表 C-6），flyway_schema_history 不应有对应记录
        Integer phantom = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version::int BETWEEN 3 AND 12
                """, Integer.class);
        assertEquals(0, phantom, "V3~V12 不应有历史记录（缺号是注册事实而非丢失）");
    }

    private int countAppliedVersion(String version) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = TRUE",
                Integer.class, version);
        return n == null ? 0 : n;
    }
}
