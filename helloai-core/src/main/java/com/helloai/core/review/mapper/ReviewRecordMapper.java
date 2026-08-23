package com.helloai.core.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.review.dto.QualityTrendPoint;
import com.helloai.core.review.dto.ReviewerLeniency;
import com.helloai.core.review.dto.ReworkRoundPoint;
import com.helloai.core.review.entity.ReviewRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecord> {

    /** 统计某任务下全部子任务的审查记录数（删除前风险提示用）。 */
    @Select("SELECT COUNT(*) FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int countByTaskId(@Param("taskId") Long taskId);

    /** 物理删除某任务下全部审查记录（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 质量趋势源（Phase 5 看板）：窗口内按天分组统计审查数/通过数/平均分。
     *
     * <p>create_time 为审计列（BaseEntity），窗口语义 {@code create_time >= now() - days}。
     * 投影 record（非 Map）：防 MyBatisPlusConfig 全局 Map→JacksonTypeHandler 劫持（§6.132）。</p>
     */
    @Select("""
            SELECT to_char(date_trunc('day', create_time), 'YYYY-MM-DD') AS period,
                   COUNT(*) AS reviewedCount,
                   COUNT(*) FILTER (WHERE result = 'APPROVED') AS approvedCount,
                   COALESCE(AVG(score), 0) AS avgScore
            FROM review_record
            WHERE deleted = 0 AND create_time >= now() - #{days} * interval '1 day'
            GROUP BY date_trunc('day', create_time)
            ORDER BY date_trunc('day', create_time) ASC
            """)
    List<QualityTrendPoint> selectTrendSource(@Param("days") int days);

    /** 窗口内非空 issues 列表（Java 层 DefectLabelParser 聚合，口径与画像一致）。 */
    @Select("""
            SELECT issues
            FROM review_record
            WHERE deleted = 0 AND create_time >= now() - #{days} * interval '1 day'
              AND issues IS NOT NULL AND issues <> ''
            """)
    List<String> selectIssuesForStats(@Param("days") int days);

    /** 窗口内按审查轮次分组计数（round 升序）。 */
    @Select("""
            SELECT round AS round, COUNT(*) AS subTaskCount
            FROM review_record
            WHERE deleted = 0 AND create_time >= now() - #{days} * interval '1 day'
              AND round IS NOT NULL
            GROUP BY round
            ORDER BY round ASC
            """)
    List<ReworkRoundPoint> selectReworkDistribution(@Param("days") int days);

    /**
     * 窗口内审查者维度通过率（Phase 5 看板 reviewer 放水率）。
     *
     * <p>approveRate 在 SQL 层算好（0-100 整数百分比）；reviewer_name 由
     * Service 层经 agent 域服务批量补名后重建 record（本查询先置空串占位）。</p>
     */
    @Select("""
            SELECT reviewer_agent_id AS reviewerAgentId,
                   CAST('' AS VARCHAR) AS reviewerName,
                   COUNT(*) AS reviewedCount,
                   COALESCE(COUNT(*) FILTER (WHERE result = 'APPROVED') * 100
                            / NULLIF(COUNT(*), 0), 0) AS approveRate,
                   COALESCE(AVG(score), 0) AS avgScore
            FROM review_record
            WHERE deleted = 0 AND create_time >= now() - #{days} * interval '1 day'
              AND reviewer_agent_id IS NOT NULL
            GROUP BY reviewer_agent_id
            ORDER BY reviewedCount DESC
            """)
    List<ReviewerLeniency> selectReviewerLeniency(@Param("days") int days);
}
