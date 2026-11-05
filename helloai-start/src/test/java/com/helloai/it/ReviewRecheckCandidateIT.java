package com.helloai.it;

import com.helloai.core.review.dto.RecheckStats;
import com.helloai.core.review.mapper.ReviewRecheckLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `RV-6` 驳回侧抽检候选 SQL 的真实库集成验证（Testcontainers + Flyway）。
 *
 * <p>覆盖五条口径：① 无快照的 REJECTED 不入样（历史驳回没有原判对象）；
 * ② 有快照但**无冻结证据**的 REJECTED 也不入样（B 口径：返工后无法复判，且会永久占用队首）；
 * ③ 含 `frozenEvidence` 的 REJECTED 入样；④ INCONCLUSIVE 不入样；⑤ 驳回优先且已抽检记录被排除。</p>
 */
@DisplayName("RV-6 抽检候选（驳回侧）真实库验证")
class ReviewRecheckCandidateIT extends AbstractItTestBase {

    // 96xx 段：与其它 IT 类隔离（容器为跨类静态单例）
    private static final long TASK = 9601L;
    private static final long MODULE = 9601L;
    private static final long SUB = 9601L;
    private static final long AGENT = 9601L;

    private static final long APPROVED_NO_SNAPSHOT = 9601L;
    private static final long REJECTED_NO_SNAPSHOT = 9602L;
    private static final long REJECTED_WITH_FROZEN = 9603L;
    private static final long INCONCLUSIVE_WITH_SNAPSHOT = 9604L;
    private static final long REJECTED_WITHOUT_FROZEN = 9605L;

    private static final long RECHECK_REJECTED = 9611L;
    private static final long RECHECK_APPROVED = 9612L;

    private static final String SNAPSHOT_JSON = "{\"schemaVersion\":1}";
    private static final String FROZEN_SNAPSHOT_JSON =
            "{\"schemaVersion\":2,\"frozenEvidence\":{\"executionOutput\":\"original\"}}";

    @Autowired
    private ReviewRecheckLogMapper recheckLogMapper;

    @BeforeEach
    void seed() {
        clearResidual();
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, 'it-rv6-task', 'IN_PROGRESS', 'it', 'it')
                """, TASK);
        jdbcTemplate.update("""
                INSERT INTO module (id, task_id, name, create_by, update_by)
                VALUES (?, ?, 'it-rv6-mod', 'it', 'it')
                """, MODULE, TASK);
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, 'it-rv6-agent', 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, AGENT);
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, module_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, ?, 'it-rv6-sub', 'REVIEW', ?, 'it', 'it')
                """, SUB, TASK, MODULE, AGENT);

        insertRecord(APPROVED_NO_SNAPSHOT, "APPROVED", null);
        insertRecord(REJECTED_NO_SNAPSHOT, "REJECTED", null);
        insertRecord(REJECTED_WITHOUT_FROZEN, "REJECTED", SNAPSHOT_JSON);
        insertRecord(REJECTED_WITH_FROZEN, "REJECTED", FROZEN_SNAPSHOT_JSON);
        insertRecord(INCONCLUSIVE_WITH_SNAPSHOT, "INCONCLUSIVE", SNAPSHOT_JSON);
    }

    private void insertRecord(long id, String result, String snapshotJson) {
        jdbcTemplate.update("""
                INSERT INTO review_record
                    (id, sub_task_id, reviewer_agent_id, result, score, round, snapshot, create_by, update_by)
                VALUES (?, ?, ?, ?, 4, 1, CAST(? AS jsonb), 'it', 'it')
                """, id, SUB, AGENT, result, snapshotJson);
    }

    private void clearResidual() {
        jdbcTemplate.update("DELETE FROM review_recheck_log WHERE sub_task_id = ?", SUB);
        jdbcTemplate.update("DELETE FROM review_record WHERE sub_task_id = ?", SUB);
        jdbcTemplate.update("DELETE FROM sub_task WHERE id = ?", SUB);
        jdbcTemplate.update("DELETE FROM module WHERE id = ?", MODULE);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK);
        jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT);
    }

    private OffsetDateTime since() {
        return OffsetDateTime.now().minusDays(1);
    }

    @Test
    @DisplayName("候选口径：仅 APPROVED 与含冻结证据的 REJECTED 入样，驳回优先，INCONCLUSIVE/无冻结排除")
    void candidatesIncludeRejectedWithFrozenEvidenceOnly() {
        assertEquals(2L, recheckLogMapper.countRecheckCandidates(since()));

        List<Long> ids = recheckLogMapper.selectRecheckCandidateIds(since(), 10);

        assertEquals(List.of(REJECTED_WITH_FROZEN, APPROVED_NO_SNAPSHOT), ids,
                "驳回优先入样；无快照/无冻结驳回与 INCONCLUSIVE 不应出现");
    }

    @Test
    @DisplayName("已抽检记录被排除，统计按原判结果分桶")
    void recheckedRecordExcludedAndStatsGrouped() {
        jdbcTemplate.update("""
                INSERT INTO review_recheck_log
                    (id, review_record_id, sub_task_id, original_result, recheck_result,
                     discrepancy, reviewer_agent, score, create_by, update_by)
                VALUES (?, ?, ?, 'REJECTED', 'APPROVED', 1, ?, 5, 'it', 'it')
                """, RECHECK_REJECTED, REJECTED_WITH_FROZEN, SUB, AGENT);
        jdbcTemplate.update("""
                INSERT INTO review_recheck_log
                    (id, review_record_id, sub_task_id, original_result, recheck_result,
                     discrepancy, reviewer_agent, score, create_by, update_by)
                VALUES (?, ?, ?, 'APPROVED', 'APPROVED', 0, ?, 4, 'it', 'it')
                """, RECHECK_APPROVED, APPROVED_NO_SNAPSHOT, SUB, AGENT);

        List<Long> ids = recheckLogMapper.selectRecheckCandidateIds(since(), 10);
        assertEquals(List.of(), ids, "两条候选都已抽检，应全部排除");

        List<RecheckStats.Row> rows = recheckLogMapper.selectRecheckStats(since());
        assertTrue(rows.stream().anyMatch(row -> "REJECTED".equals(row.originalResult())
                && row.sampled() == 1 && row.divergent() == 1));
        assertTrue(rows.stream().anyMatch(row -> "APPROVED".equals(row.originalResult())
                && row.sampled() == 1 && row.divergent() == 0));
    }
}