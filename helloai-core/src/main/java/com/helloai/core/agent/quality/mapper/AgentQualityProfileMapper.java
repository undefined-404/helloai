package com.helloai.core.agent.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.quality.dto.AgentQualityRank;
import com.helloai.core.agent.quality.dto.QualityOverview;
import com.helloai.core.agent.quality.dto.RebuildSourceRow;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Agent 质量画像 Mapper（反馈回路第 1 层，V54）。
 *
 * <p>核心计数采用单条 UPDATE 原子增量（规避并发读改写竞态），
 * issue_defect_stats 采用 JSONB LATERAL 原子合并（数值累加而非覆盖）。</p>
 */
@Mapper
public interface AgentQualityProfileMapper extends BaseMapper<AgentQualityProfile> {

    /**
     * 核心计数原子增量：单条 UPDATE 内完成全部计数累加，
     * 并发评审时由 PG 行锁串行化，不存在读改写丢失更新。
     *
     * <p>防重条件：{@code last_review_record_id < #{lastReviewRecordId}} 保证
     * 同一 review_record 重复回调不重复计数（幂等判定）。</p>
     */
    @Update("""
            UPDATE agent_quality_profile SET
                reviewed_count = reviewed_count + #{reviewedDelta},
                approved_count = approved_count + #{approvedDelta},
                first_reviewed_count = first_reviewed_count + #{firstReviewedDelta},
                first_pass_count = first_pass_count + #{firstPassDelta},
                total_score = total_score + #{scoreDelta},
                rework_round_sum = rework_round_sum + #{reworkDelta},
                last_review_record_id = #{lastReviewRecordId},
                update_by = #{updateBy}
            WHERE agent_id = #{agentId} AND deleted = 0
              AND (last_review_record_id IS NULL OR last_review_record_id < #{lastReviewRecordId})
            """)
    int incrementCore(@Param("agentId") Long agentId,
                      @Param("reviewedDelta") int reviewedDelta,
                      @Param("approvedDelta") int approvedDelta,
                      @Param("firstReviewedDelta") int firstReviewedDelta,
                      @Param("firstPassDelta") int firstPassDelta,
                      @Param("scoreDelta") int scoreDelta,
                      @Param("reworkDelta") int reworkDelta,
                      @Param("lastReviewRecordId") Long lastReviewRecordId,
                      @Param("updateBy") String updateBy);

    /**
     * issue_defect_stats 原子合并：JSONB 逐 key 数值累加（同 key 相加而非覆盖）。
     *
     * <p>以旧值 key 与新入参 key 的并集为遍历域，逐 key 相加后 jsonb_object_agg
     * 聚合写回；与 {@link #incrementCore} 一样是单条 UPDATE，并发安全。仅遍历
     * 入参 key 会丢弃旧 map 中本次未出现的标签（覆盖语义），真实环境已踩坑
     * （迭代记录 §6.129），故用并集口径。</p>
     */
    @Update("""
            UPDATE agent_quality_profile p SET
                issue_defect_stats = (
                    SELECT jsonb_object_agg(all_keys.k,
                            COALESCE((p.issue_defect_stats ->> all_keys.k)::int, 0)
                          + COALESCE((incoming.json ->> all_keys.k)::int, 0))
                    FROM (SELECT CAST(#{statsJson} AS jsonb) AS json) incoming
                    CROSS JOIN LATERAL (
                        SELECT jsonb_object_keys(p.issue_defect_stats) AS k
                        UNION
                        SELECT jsonb_object_keys(incoming.json) AS k
                    ) all_keys
                ),
                update_by = #{updateBy}
            WHERE p.agent_id = #{agentId} AND p.deleted = 0
            """)
    int mergeDefectStats(@Param("agentId") Long agentId,
                         @Param("statsJson") String statsJson,
                         @Param("updateBy") String updateBy);

    /**
     * Reviewer 维度计数原子增量（反馈回路 Phase 4 双审/抽检）：
     * reviewer_reviewed_count / reviewer_disagreement_count 单条 UPDATE 累加，
     * 并发由 PG 行锁串行化；调用方（review 域经 Service 接口）best-effort 包裹。
     *
     * <p>画像行不存在时返回 0（调用方决定是否 INSERT 兜底）。</p>
     */
    @Update("""
            UPDATE agent_quality_profile SET
                reviewer_reviewed_count = reviewer_reviewed_count + #{reviewedDelta},
                reviewer_disagreement_count = reviewer_disagreement_count + #{disagreementDelta},
                update_by = #{updateBy}
            WHERE agent_id = #{agentId} AND deleted = 0
            """)
    int incrementReviewerStats(@Param("agentId") Long agentId,
                               @Param("reviewedDelta") int reviewedDelta,
                               @Param("disagreementDelta") int disagreementDelta,
                               @Param("updateBy") String updateBy);

    /**
     * 重算数据源：该执行者（sub_task.assigned_agent_id 当前归属）名下全部
     * review_record，按记录 id 升序（保证重算顺序与历史落库顺序一致）。
     *
     * <p>返回具体 DTO 而非 List&lt;Map&gt;：Map 返回会被 MyBatisPlusConfig 的全局
     * Map→JacksonTypeHandler 注册劫持（整行按 JSON 解析首列），真实环境已炸 500。</p>
     */
    @Select("""
            SELECT r.id AS record_id, r.result AS result, r.score AS score,
                   r.round AS round, r.issues AS issues
            FROM review_record r
            JOIN sub_task st ON st.id = r.sub_task_id
            WHERE st.assigned_agent_id = #{agentId}
              AND r.deleted = 0 AND st.deleted = 0
            ORDER BY r.id ASC
            """)
    List<RebuildSourceRow> selectRebuildSource(@Param("agentId") Long agentId);

    /**
     * 全局质量概览（Phase 5 看板 overview）：画像表存量聚合，单行必返回。
     *
     * <p>COALESCE 兜底保证空表也返回一行 0 值；投影 record（非 Map），
     * 防 Map→JacksonTypeHandler 劫持（§6.132）。</p>
     */
    @Select("""
            SELECT COALESCE(SUM(reviewed_count), 0) AS totalReviewed,
                   COALESCE(SUM(approved_count), 0) AS totalApproved,
                   COALESCE(SUM(first_pass_count) * 100 / NULLIF(SUM(first_reviewed_count), 0), 0) AS firstPassRate,
                   COALESCE(SUM(rework_round_sum) * 1.0 / NULLIF(SUM(reviewed_count), 0), 0) AS avgReworkRounds,
                   COUNT(*) AS activeExecutors
            FROM agent_quality_profile
            WHERE deleted = 0
            """)
    QualityOverview selectOverviewRow();

    /**
     * Agent 质量排行（Phase 5 看板 agents）：一次通过率降序 → 审查数降序 → agentId 升序。
     *
     * <p>agentName/qualityScore 为占位：前者由 Service 层经 AgentService 批量补名，
     * 后者由 Service 层逐行调 {@code computeQualityScore} 重算（口径唯一，防 SQL 漂移）。
     * limit &lt;= 0 返回全部（LIMIT 子句动态拼接）。</p>
     */
    @Select("""
            <script>
            SELECT p.agent_id AS agentId,
                   CAST('' AS VARCHAR) AS agentName,
                   p.reviewed_count AS reviewedCount,
                   COALESCE(p.first_pass_count * 100 / NULLIF(p.first_reviewed_count, 0), 0) AS firstPassRate,
                   0 AS qualityScore
            FROM agent_quality_profile p
            WHERE p.deleted = 0 AND p.reviewed_count > 0
            ORDER BY firstPassRate DESC, p.reviewed_count DESC, p.agent_id ASC
            <if test="limit != null and limit > 0">LIMIT #{limit}</if>
            </script>
            """)
    List<AgentQualityRank> selectRankingRows(@Param("limit") Integer limit);
}
