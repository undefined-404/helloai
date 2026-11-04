package com.helloai.core.review.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.ReviewResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Reviewer 抽检日志实体（反馈回路，V57）。
 *
 * <p>一行 = 一次抽检复审：记录被抽检 review_record 的原判、复审判定与
 * 分歧标记。`RV-6` 起双向抽检：原 APPROVED→REJECTED 记放水，
 * 原 REJECTED→APPROVED 记误伤，方向由 {@code originalResult} 区分。
 * 度量 Reviewer 偏差并驱动画像表 reviewer 维度计数增量。归属 review 域（§6.146 域迁移：
 * 评审相关实体/服务/映射器归位 review 域，与 {@link ReviewRecord} 同域）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("review_recheck_log")
public class ReviewRecheckLog extends BaseEntity {

    /** 被抽检的审查记录 ID（review_record.id）。 */
    private Long reviewRecordId;

    /** 被抽检子任务 ID。 */
    private Long subTaskId;

    /** 原判结果（被抽检 record 的 result）。 */
    private ReviewResult originalResult;

    /** 复审判定结果。 */
    private ReviewResult recheckResult;

    /**
     * 分歧标记：1=复判与原判不一致，0=一致。
     *
     * <p>方向由 {@link #originalResult} 区分：原 {@code APPROVED} → 复判 {@code REJECTED}
     * 记放水；原 {@code REJECTED} → 复判 {@code APPROVED} 记误伤（`RV-6`）。</p>
     */
    private Integer discrepancy;

    /** 执行复审的 Reviewer Agent ID。命名不符 {@code *Id} 约定，须显式声明为字符串（雪花 ID 精度）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reviewerAgent;

    /** 复审评分（1-5）。 */
    private Integer score;

    /** 复审驳回时的问题描述。 */
    private String issues;

    /** 复审意见。 */
    private String comment;
}
