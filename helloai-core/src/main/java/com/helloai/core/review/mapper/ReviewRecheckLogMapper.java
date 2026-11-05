package com.helloai.core.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.review.dto.RecheckStats;
import com.helloai.core.review.entity.ReviewRecheckLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reviewer 抽检日志 Mapper（反馈回路，V57）。
 *
 * <p>抽检候选查询收口在本域：review_record / review_recheck_log 同属 review 域
 * （§6.146 域迁移），本 Mapper 仅供 review 域自查自表；跨域（job）一律走
 * {@code ReviewService} 接口方法，禁止直捅本 Mapper（§3.x 跨域红线）。</p>
 */
@Mapper
public interface ReviewRecheckLogMapper extends BaseMapper<ReviewRecheckLog> {

    /**
     * 物理删除某任务下全部抽检日志（cascade：{@code review_recheck_log.sub_task_id} <b>有外键</b>
     * 引用 {@code sub_task.id}）。
     *
     * <p>2026-10-05 D-1：此前任务级联删除<b>完全未清理本表</b>（42 行现网数据，虽当前 0 孤儿），
     * 一旦所辖子任务存在抽检日志，删 sub_task 即撞外键 → HTTP 500（与 P2-4 同类隐患）。
     * 须在 {@code sub_task} 行删除之前执行。</p>
     */
    @Delete("DELETE FROM review_recheck_log WHERE sub_task_id IN "
            + "(SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 抽检候选计数：窗口内可复判的 review_record 数。
     *
     * <p>`RV-6`：候选从「仅 APPROVED」扩为 APPROVED / REJECTED 双向抽检。
     * 驳回侧加**冻结证据硬前置**（`snapshot -> 'frozenEvidence' IS NOT NULL`，2026-11-05 口径 B）——
     * 返工已覆盖产出、附件已失效，只有冻结了原判五段证据的记录才能构成有效复判对象。
     * 旧的无冻结快照驳回记录也排除：否则它会因「驳回优先 + 跳过不写日志」永久占据队首，
     * 每 tick 被取一次又跳过，挡住后续有效样本。</p>
     *
     * <p>`NOT EXISTS` 排除已抽检记录（同一 record 单窗口内至多抽一次；
     * 时间窗口推进后旧记录可再次入选）。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM review_record r
            WHERE r.result IN ('APPROVED', 'REJECTED') AND r.deleted = 0
              AND r.create_time >= #{since}
              AND (r.result = 'APPROVED' OR r.snapshot -> 'frozenEvidence' IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM review_recheck_log l
                              WHERE l.review_record_id = r.id AND l.deleted = 0)
            """)
    long countRecheckCandidates(@Param("since") OffsetDateTime since);

    /**
     * 抽检候选 ID 列表：`RV-6` 驳回优先入样（驳回是少数侧，先审先抽会让
     * 误伤率迟迟没有样本），同结果类型内按落库时间升序；LIMIT 由调用方按
     * 抽样比例折算后的批量大小传入。
     */
    @Select("""
            SELECT r.id
            FROM review_record r
            WHERE r.result IN ('APPROVED', 'REJECTED') AND r.deleted = 0
              AND r.create_time >= #{since}
              AND (r.result = 'APPROVED' OR r.snapshot -> 'frozenEvidence' IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM review_recheck_log l
                              WHERE l.review_record_id = r.id AND l.deleted = 0)
            ORDER BY (r.result = 'REJECTED') DESC, r.create_time ASC
            LIMIT #{limit}
            """)
    List<Long> selectRecheckCandidateIds(@Param("since") OffsetDateTime since,
                                         @Param("limit") int limit);

    /**
     * 抽检偏差统计（`RV-6`）：窗口内按原判结果分组，统计有效复判数与分歧数。
     *
     * <p>分母只含已落 {@code review_recheck_log} 的有效复判；跳过（无 reviewer /
     * 不可判定 / 快照漂移 / 无快照）不写日志，天然不进分母。</p>
     */
    @Select("""
            SELECT original_result,
                   COUNT(*) AS sampled,
                   COUNT(*) FILTER (WHERE discrepancy = 1) AS divergent
            FROM review_recheck_log
            WHERE deleted = 0 AND create_time >= #{since}
            GROUP BY original_result
            """)
    List<RecheckStats.Row> selectRecheckStats(@Param("since") OffsetDateTime since);
}
