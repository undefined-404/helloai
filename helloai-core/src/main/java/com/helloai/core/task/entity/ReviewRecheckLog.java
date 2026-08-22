package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.ReviewResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Reviewer 抽检日志实体（反馈回路 Phase 4，V57）。
 *
 * <p>一行 = 一次抽检复审：记录被抽检 review_record 的原判、复审判定与
 * 放水标记（原 APPROVED 复审 REJECTED），度量 Reviewer 放水率并驱动
 * 画像表 reviewer 维度计数增量。归属 task 域（与 {@link ReviewRecord}
 * 同域先例，V54 注释明确「评审相关实体归 task 域」）。</p>
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

    /** 放水标记：1=原 APPROVED 复审 REJECTED，0=一致。 */
    private Integer discrepancy;

    /** 执行复审的 Reviewer Agent ID。 */
    private Long reviewerAgent;

    /** 复审评分（1-5）。 */
    private Integer score;

    /** 复审驳回时的问题描述。 */
    private String issues;

    /** 复审意见。 */
    private String comment;
}
