package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动核验双审与抽检配置（反馈回路）。
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

    /** 是否启用 HIGH 任务双审。默认 true；关闭后与开启前行为一致（单审）。 */
    private boolean dualReviewEnabled = true;

    /** 双审共识策略。默认 REQUIRE_BOTH。 */
    private DualReviewConsensusPolicy dualReviewConsensusPolicy = DualReviewConsensusPolicy.REQUIRE_BOTH;

    /**
     * 核验互斥锁 TTL <b>下限</b>（秒）。默认 <b>240</b>。
     *
     * <p><b>★不变量（`MED-3`，2026-10-15 修复）：锁 TTL 不得短于单次核验 LLM 调用的最坏时长。</b>
     * 原实现硬编码 120s，而读取超时默认 {@code 180000ms}（{@code AgentProviderProperties
     * .ProviderConfig.readTimeoutMs}）⇒ <b>180s &gt; 120s</b>：单审调用可合法跑到 180s，
     * 锁却在 120s 就过期，中间留出窗口让 L1 事件 / L2 MQ / L3 孤儿扫描抢到已过期锁，
     * 对同一子任务并发核验（重复 {@code review_record} → 画像重复计数）。</p>
     *
     * <p><b>★本值语义 = 下限，不是精确值</b>：有效 TTL 由
     * {@code ReviewLockTtlResolver} 计算为
     * {@code max(本值, 各 provider 读超时最大值 + 1s 余量)}。
     * 之所以不采用「配置不对就 fail-fast」，是因为 {@code read-timeout-ms} <b>按 profile 分档</b>
     * （默认 180s、{@code application-dev.yml} 为整合报告长输出设 600s，且该文件是
     * {@code skip-worktree} 的本地私有件、不可提交）⇒ fail-fast 会导致 fresh clone 的
     * dev profile 起不来。该不变量方向唯一（只能调大），故自动抬高是安全的，同时 WARN 留痕。</p>
     *
     * <p><b>为什么原先没暴露</b>：{@link #dualReviewTimeoutSeconds} 早已按同一推理收紧到 90s
     * （见其 javadoc），但那条不变量是按<b>双审路径</b>推导的 —— 单审路径<b>没有 deadline</b>，
     * 其时长上界由 provider 读超时决定，故双审安全<b>不代表</b>单审安全。
     * <b>同一功能的两条路径，安全属性要分开验证。</b></p>
     */
    private long lockTtlSeconds = 240;

    /**
     * 双审单侧核验超时（秒）。默认 90。
     *
     * <p><b>必须严格收进核验互斥锁 TTL（{@link #lockTtlSeconds}）内</b>：
     * deadline 在加锁之后才起算，若超时 ≥ 锁 TTL，双审窗口必然超出锁有效期，
     * 锁释放后 L1/L2/L3 触发源可抢到已过期锁重复双审（重复 review_record →
     * 画像重复计数）。90s 保证整个双审窗口（等待 + 落库 + 时间线）落在锁内。</p>
     */
    private long dualReviewTimeoutSeconds = 90;

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
