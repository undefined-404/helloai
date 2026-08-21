package com.helloai.core.agent.quality.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Agent 质量画像实体（反馈回路第 1 层，V54）。
 *
 * <p>随 review_record 落库同事务增量维护（{@code QualityProfileUpdater} 收口在
 * ReviewServiceImpl.recordAutoReview / createReview 两处），作为调度回灌
 * （AgentSelector qualityRank / 动态 TTL 复合分）与历史表现摘要注入的数据源。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_quality_profile")
public class AgentQualityProfile extends BaseEntity {

    /** 被评审的执行 Agent ID（取 sub_task.assigned_agent_id 落库时刻归属）。 */
    private Long agentId;

    /** 累计被评审次数。 */
    private Integer reviewedCount;

    /** 累计通过（APPROVED）次数。 */
    private Integer approvedCount;

    /** 首轮评审（round=1）累计次数。 */
    private Integer firstReviewedCount;

    /** 首轮即通过（round=1 且 APPROVED）累计次数。 */
    private Integer firstPassCount;

    /** 评审评分累加（score 总和）。 */
    private Integer totalScore;

    /** 返工轮次累计（round>1 的轮次贡献值）。 */
    private Integer reworkRoundSum;

    /** issues 四元组 [defect] 标签计数（JSONB map: 标签名 -> 出现次数）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Integer> issueDefectStats;

    /** 作为 Reviewer 累计核验次数（Phase 4 双审/抽检预留）。 */
    private Integer reviewerReviewedCount;

    /** 作为 Reviewer 产生分歧次数（Phase 4 双审预留）。 */
    private Integer reviewerDisagreementCount;

    /** 最近一次纳入统计的 review_record.id（增量更新幂等判定 + 对账起点）。 */
    private Long lastReviewRecordId;
}
