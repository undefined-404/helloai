package com.helloai.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1：Flyway 迁移全量 apply（审计建议 #5-1）。
 *
 * <p><b>被测事实</b>：helloai-start 是唯一携带 Flyway 迁移脚本的模块（V3~V12 为历史缺号，
 * 见差距表 C-6）。B 级集成在空 PG 容器上从零执行完整迁移链，一次性暴露「只在新库上才炸」的
 * 脚本缺陷（缺列 / 缺索引 / CHECK 约束冲突 / 幂等性缺失，CODE_STYLE §16）。脚本数量沿
 * classpath 动态统计，不硬编码。</p>
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

        Set<String> resolvedVersions = Arrays.stream(scripts)
                .map(Resource::getFilename)
                .map(name -> name.substring(1, name.indexOf("__")))
                .collect(Collectors.toSet());
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                String.class);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE",
                Integer.class);

        // 只比数量会漏掉「多一条幽灵记录 + 少一条真实记录」的漂移：总数不变，集合已错位
        assertEquals(resolvedVersions, new HashSet<>(appliedVersions),
                "classpath 迁移版本集合应与成功 apply 集合完全一致");
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

        // 2026-11-11 收敛：基线直接为最终形态，必须有暂存区、且执行表不再有过渡列/旧状态值
        assertTrue(tableExists("task_plan_draft"), "V147: 草案暂存区表应存在");
        assertFalse(columnExists("sub_task", "decompose_attempt_id"),
                "合并基线不应再建 sub_task.decompose_attempt_id");
        assertFalse(columnExists("sub_task", "draft_seq"),
                "合并基线不应再建 sub_task.draft_seq");
        String subStatusCheck = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_sub_task_status'",
                String.class);
        assertNotNull(subStatusCheck, "chk_sub_task_status 必须存在");
        assertFalse(subStatusCheck.contains("PENDING_PLAN_REVIEW"),
                "最终 CHECK 不得再接受已退役的 PENDING_PLAN_REVIEW（V148 曾重复重建把它加回来）");
        assertTrue(subStatusCheck.contains("AWAITING_DERIVED")
                        && subStatusCheck.contains("RESTRUCTURING"),
                "最终 CHECK 必须包含派生/重构两态");
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
