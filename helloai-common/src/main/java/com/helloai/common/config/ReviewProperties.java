package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动核验双审与抽检配置（反馈回路 Phase 4）。
 *
 * <p>双审：difficulty=HIGH 且未指定 reviewerAgentId 的子任务自动选两个
 * 不同模型（modelType）的 API_KEY_LLM Reviewer 独立核验，一致按共识走
 * 既有通过/驳回链，分歧停 REVIEW 转人工介入；候选不足 2 个降级单审。</p>
 *
 * <p>抽检：helloai-job 的 {@code ReviewerRecheckTask} 按固定周期对窗口内
 * APPROVED 审查记录抽样复审，度量放水率并驱动画像表 reviewer 维度计数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.review")
public class ReviewProperties {

    /** 双审共识策略。 */
    public enum DualReviewConsensusPolicy {
        /** 两审一致才按共识落地；分歧转人工（默认，从严）。 */
        REQUIRE_BOTH,
        /** 任一通过即按通过落地；双拒驳回。 */
        ANY
    }

    /** 是否启用 HIGH 任务双审。默认 true；关闭后与 Phase 4 前行为一致（单审）。 */
    private boolean dualReviewEnabled = true;

    /** 双审共识策略。默认 REQUIRE_BOTH。 */
    private DualReviewConsensusPolicy dualReviewConsensusPolicy = DualReviewConsensusPolicy.REQUIRE_BOTH;

    /**
     * 双审单侧核验超时（秒）。默认 120，与核验互斥锁 TTL 对齐
     * （超时边界 ≤ 锁 TTL，防锁释放后核验线程仍在跑）。
     */
    private long dualReviewTimeoutSeconds = 120;

    /** 是否启用 Reviewer 抽检任务。默认 true。 */
    private boolean recheckEnabled = true;

    /** 抽检扫描周期（毫秒）。默认 1 小时。 */
    private long recheckIntervalMs = 3_600_000;

    /** 单轮抽检抽样比例（对窗口内 APPROVED 存量）。默认 0.05（5%）。 */
    private double recheckSampleRatio = 0.05;

    /** 单轮抽检批量上限（防单轮 LLM 调用过多阻塞调度）。默认 20。 */
    private int recheckMaxBatch = 20;

    /** 抽检时间窗口（天）：只抽近 N 天内落库的 APPROVED 记录。默认 7。 */
    private int recheckWindowDays = 7;
}
