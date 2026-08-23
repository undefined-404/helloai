package com.helloai.core.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.review.entity.ReviewRecheckLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reviewer 抽检日志 Mapper（反馈回路 Phase 4，V57）。
 *
 * <p>抽检候选查询收口在本域：review_record / review_recheck_log 同属 review 域
 * （§6.146 域迁移），本 Mapper 仅供 review 域自查自表；跨域（job）一律走
 * {@code ReviewService} 接口方法，禁止直捅本 Mapper（§3.x 跨域红线）。</p>
 */
@Mapper
public interface ReviewRecheckLogMapper extends BaseMapper<ReviewRecheckLog> {

    /**
     * 抽检候选计数：窗口内 APPROVED 且未被任何抽检记录覆盖的 review_record 数。
     *
     * <p>NOT EXISTS 排除已抽检记录（同一 record 单窗口内至多抽一次；
     * 时间窗口推进后旧记录可再次入选）。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM review_record r
            WHERE r.result = 'APPROVED' AND r.deleted = 0
              AND r.create_time >= #{since}
              AND NOT EXISTS (SELECT 1 FROM review_recheck_log l
                              WHERE l.review_record_id = r.id AND l.deleted = 0)
            """)
    long countRecheckCandidates(@Param("since") OffsetDateTime since);

    /**
     * 抽检候选 ID 列表：按落库时间升序（先审先抽，避免窗口边缘抖动），
     * LIMIT 由调用方按抽样比例折算后的批量大小传入。
     */
    @Select("""
            SELECT r.id
            FROM review_record r
            WHERE r.result = 'APPROVED' AND r.deleted = 0
              AND r.create_time >= #{since}
              AND NOT EXISTS (SELECT 1 FROM review_recheck_log l
                              WHERE l.review_record_id = r.id AND l.deleted = 0)
            ORDER BY r.create_time ASC
            LIMIT #{limit}
            """)
    List<Long> selectRecheckCandidateIds(@Param("since") OffsetDateTime since,
                                         @Param("limit") int limit);
}
