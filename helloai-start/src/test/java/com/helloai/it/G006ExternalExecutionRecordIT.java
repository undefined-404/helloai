package com.helloai.it;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-006：外部执行轨迹对齐（agent_execution_record 外部路径）。
 *
 * <p><b>被测事实</b>：外部 Agent 认领即开 attempt（直接 RUNNING）、提交即收口、
 * 平台内（API_KEY_LLM）记录不被外部收口逻辑越界改写、防重复发命令的
 * {@code hasPendingOrRunning} 不吃外部 attempt 行。</p>
 *
 * <p><b>为什么必须真实 DB</b>：这些断言落在「条件更新 + 状态过滤」的 SQL 面上——
 * {@code access_type} 的 NULL 分支、{@code IN (PENDING,RUNNING)} 过滤、
 * {@code markSuccess/markTimeout} 的 CAS，mock 上只能验证「方法被调用过」，
 * 无法证明「哪一行被改、哪一行没被改」，而那正是本需求的风险点
 * （误改平台内记录会让内部执行被外部收口截断）。</p>
 *
 * <p><b>ID 段（2026-10-22 修正）</b>：本类原用 9401 段，与
 * {@link TaskRunningSpecPhaseBIT}（9401~9406）撞号且无 {@code @AfterEach} 清理，
 * 残留 {@code sub_task(task_id=9401/9402)} 使后者 seed 的
 * {@code DELETE FROM task WHERE id IN (9401,9402)} 撞 {@code sub_task_task_id_fkey}
 * （全量 *IT 实跑暴露，CI 门禁 5 失败）。现迁至空段 9601 并补 {@code @AfterEach}
 * 清场（按 FK 依赖顺序，先删 agent_execution_record 再删 sub_task/agent/task）。</p>
 */
@DisplayName("G-006: 外部执行 attempt 生命周期")
class G006ExternalExecutionRecordIT extends AbstractItTestBase {

    private static final long TASK_ID = 9601L;
    private static final long AGENT_ID = 9601L;
    private static final long SUB_TASK_ID = 9601L;

    @Autowired
    private AgentExecutionRecordService agentExecutionRecordService;

    @Test
    @DisplayName("认领开行：外部 accessType 落 RUNNING、worker_node 带 external: 前缀、重复开启幂等")
    void openExternalAttemptCreatesRunningRowAndIsIdempotent() {
        seedAssignedSubTask(TASK_ID, AGENT_ID, SUB_TASK_ID);

        AgentExecutionRecord first = agentExecutionRecordService.openExternalAttempt(
                SUB_TASK_ID, AGENT_ID, AgentAccessType.CLI_CLIENT, "mcp-claim", "external:it-agent-9401");
        assertNotNull(first, "外部尝试应开行");
        assertNotNull(first.getId());
        assertEquals(ExecutionStatus.RUNNING, first.getStatus(), "认领即执行起点 ⇒ 直接 RUNNING");
        assertNotNull(first.getStartTime(), "RUNNING 必须带 start_time");
        assertNull(first.getEndTime(), "未收口不得有 end_time");
        assertTrue(first.getWorkerNode().startsWith("external:"),
                "外部执行者无平台主机名，worker_node 用 external:<agentName> 标识");

        // 幂等：重连/重复认领不得再开一行（否则一次执行被计成多次，污染质量/成本画像）
        AgentExecutionRecord second = agentExecutionRecordService.openExternalAttempt(
                SUB_TASK_ID, AGENT_ID, AgentAccessType.CLI_CLIENT, "mcp-claim-resume", "external:it-agent-9401");
        assertEquals(first.getId(), second.getId(), "已有 open 行时必须复用");
        assertEquals(1, countRecords(SUB_TASK_ID), "同一子任务只允许一条 open 行");
    }

    @Test
    @DisplayName("平台内执行者（API_KEY_LLM）不开外部行：其记录由派发链自己维护")
    void apiKeyLlmAgentDoesNotOpenExternalAttempt() {
        seedAssignedSubTask(TASK_ID + 1, AGENT_ID + 1, SUB_TASK_ID + 1);

        AgentExecutionRecord record = agentExecutionRecordService.openExternalAttempt(
                SUB_TASK_ID + 1, AGENT_ID + 1, AgentAccessType.API_KEY_LLM, "mcp-claim", null);

        assertNull(record, "API_KEY_LLM 属平台内路径，不应由本方法建行");
        assertEquals(0, countRecords(SUB_TASK_ID + 1));
    }

    @Test
    @DisplayName("提交收口：SUCCESS/FAILED 关闭外部行并留痕，不触碰平台内 open 行")
    void closeExternalAttemptOnlyFinalizesExternalRows() {
        long externalSubTaskId = SUB_TASK_ID + 2;
        long internalSubTaskId = SUB_TASK_ID + 3;
        seedAssignedSubTask(TASK_ID + 2, AGENT_ID + 2, externalSubTaskId);
        seedAssignedSubTask(TASK_ID + 3, AGENT_ID + 3, internalSubTaskId);

        agentExecutionRecordService.openExternalAttempt(
                externalSubTaskId, AGENT_ID + 2, AgentAccessType.CLI_CLIENT, "mcp-claim", "external:it-9402");
        // 平台内 RUNNING 行（模拟内部执行进行中）
        long internalRecordId = seedRunningPlatformRecord(internalSubTaskId, AGENT_ID + 3);

        int closed = agentExecutionRecordService.closeExternalAttempt(
                externalSubTaskId, ExecutionStatus.SUCCESS, null, 1234);
        assertEquals(1, closed, "应恰好收口一条外部 attempt");
        assertEquals("SUCCESS", recordStatus(externalSubTaskId));
        assertEquals(1234, tokenUsage(externalSubTaskId), "外部回报的 token 用量应落库（成本画像原料）");

        // 平台内行不得被外部收口越界改写
        assertTrue(agentExecutionRecordService.hasPendingOrRunning(internalSubTaskId),
                "平台内 RUNNING 行必须保持 open");
        assertEquals("RUNNING", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_execution_record WHERE id = ?", String.class, internalRecordId));

        // 幂等：无 open 行时收口返回 0，不报错
        assertEquals(0, agentExecutionRecordService.closeExternalAttempt(
                externalSubTaskId, ExecutionStatus.SUCCESS, null, null));
    }

    @Test
    @DisplayName("防重发口径：外部 attempt 不算「已有一条待消费命令」，平台内记录才算")
    void hasPendingOrRunningIgnoresExternalAttempt() {
        long externalSubTaskId = SUB_TASK_ID + 4;
        long internalSubTaskId = SUB_TASK_ID + 5;
        seedAssignedSubTask(TASK_ID + 4, AGENT_ID + 4, externalSubTaskId);
        seedAssignedSubTask(TASK_ID + 5, AGENT_ID + 5, internalSubTaskId);

        agentExecutionRecordService.openExternalAttempt(
                externalSubTaskId, AGENT_ID + 4, AgentAccessType.CLI_CLIENT, "mcp-claim", null);
        seedRunningPlatformRecord(internalSubTaskId, AGENT_ID + 5);

        // 关键回归：若外部行也计入，外部 Agent 领了任务后就再也无法被阈值回退/人工改派到平台 LLM
        assertFalse(agentExecutionRecordService.hasPendingOrRunning(externalSubTaskId),
                "外部 attempt 不对应平台命令，不得挡住重新派发");
        assertTrue(agentExecutionRecordService.hasPendingOrRunning(internalSubTaskId),
                "平台内 RUNNING 命令仍须防重");
    }

    @Test
    @DisplayName("超时收口：TIMEOUT 关闭 open 行（租约回收/2h 无更新的落点）")
    void timeoutClosesOpenExternalAttempt() {
        long subTaskId = SUB_TASK_ID + 6;
        seedAssignedSubTask(TASK_ID + 6, AGENT_ID + 6, subTaskId);
        agentExecutionRecordService.openExternalAttempt(
                subTaskId, AGENT_ID + 6, AgentAccessType.CLI_CLIENT, "mcp-claim", null);

        assertEquals(1, agentExecutionRecordService.closeExternalAttempt(
                subTaskId, ExecutionStatus.TIMEOUT, "reclaimed_lease_expired", null));
        assertEquals("TIMEOUT", recordStatus(subTaskId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT end_time FROM agent_execution_record WHERE sub_task_id = ?", Object.class, subTaskId),
                "收口必须写 end_time");
    }

    // ==================== 清场 ====================

    /**
     * 用例后清场：本类 seed 的 9601 段数据必须回收，否则残留行污染外层共享容器
     * （所有 *IT 共享同一 Testcontainers 库；本类最大用到 SUB_TASK_ID+6=9607、
     * TASK_ID+6=9607、AGENT_ID+6=9607，record id 另加 5000 → 14601~14607）。
     *
     * <p>按 FK 依赖顺序（子 → 父）删除，避免 {code task} 残留 sub_task 引用触发
     * {@code sub_task_task_id_fkey}（2026-10-22 全量 *IT 实跑暴露的撞段教训）。</p>
     */
    @AfterEach
    void cleanup() {
        for (long i = 0; i <= 6; i++) {
            jdbcTemplate.update("DELETE FROM agent_execution_record WHERE sub_task_id = ?", SUB_TASK_ID + i);
            jdbcTemplate.update("DELETE FROM sub_task WHERE id = ?", SUB_TASK_ID + i);
            jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT_ID + i);
            jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK_ID + i);
        }
    }

    // ==================== 局部辅助 ====================

    private long seedRunningPlatformRecord(long subTaskId, long agentId) {
        long recordId = subTaskId + 5000;
        jdbcTemplate.update("""
                INSERT INTO agent_execution_record
                    (id, event_id, sub_task_id, agent_id, status, worker_node, retry_count,
                     trigger_type, access_type, start_time, create_by, update_by)
                VALUES (?, ?, ?, ?, 'RUNNING', 'it-node', 0, 'assigned', 'API_KEY_LLM', now(), 'it', 'it')
                """, recordId, "it-" + subTaskId, subTaskId, agentId);
        return recordId;
    }

    private int countRecords(long subTaskId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_execution_record WHERE sub_task_id = ? AND deleted = 0",
                Integer.class, subTaskId);
        return n != null ? n : 0;
    }

    private String recordStatus(long subTaskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM agent_execution_record WHERE sub_task_id = ? AND deleted = 0",
                String.class, subTaskId);
    }

    private Integer tokenUsage(long subTaskId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT token_usage FROM agent_execution_record WHERE sub_task_id = ? AND deleted = 0",
                subTaskId);
        Object value = row.get("token_usage");
        return value == null ? null : ((Number) value).intValue();
    }
}
