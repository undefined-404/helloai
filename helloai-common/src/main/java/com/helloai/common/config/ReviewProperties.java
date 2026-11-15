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
     * 核验互斥锁 TTL 下限（秒，`MED-3`）。默认 240。
     *
     * <p>不变量：锁 TTL 不得短于单次核验 LLM 调用最坏时长，否则锁先过期、L1/L2/L3 触发源抢到已过期锁
     * 对同一子任务并发核验（重复 {@code review_record} → 画像重复计数）。本值是<b>下限</b>非精确值：
     * 有效 TTL 由 {@code ReviewLockTtlResolver} 算为 {@code max(本值, provider 读超时最大值 × 调用次数 + 1s)}，
     * 调用次数 = 1 或 2（{@link #unparseableRetryEnabled} 开启时即时重试，最坏时长翻倍）。
     * 不 fail-fast 因 {@code read-timeout-ms} 按 profile 分档（dev 为长输出设 600s 且是 skip-worktree 私有件），
     * fail-fast 会让 fresh clone 的 dev 起不来；不变量方向唯一（只能调大）故自动抬高安全，WARN 留痕。
     * 单审路径无 deadline、上界由读超时决定，与双审（{@link #dualReviewTimeoutSeconds}）安全属性须分开验证。</p>
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

    /**
     * 核验输出不可解析时是否即时重试一次。默认 true。
     *
     * <p>核验模型带工具轮次时常见「调用成功（{@code isSuccess=true}）但输出思维链/丢 JSON 格式」，
     * {@code VerdictParser} 取不到 {@code pass}；即时重试免去等 L3 孤儿扫描（最坏 60s 且可能停 REVIEW 无人接手）。
     * 只对「成功但不可解析」重试，不对调用失败/超时重试（后者已落 {@code sub_task_auto_review_execution_failed}，
     * 重试会放大读超时窗口）；重试 prompt 末尾追加格式强化段（不改判定规则），仍不可解析才落
     * {@code sub_task_auto_review_unparseable}（{@code attempts=2}），成功则第二轮以 {@code *_retry} 类型落库可复盘。
     * 开启后单次核验最坏时长翻倍，{@link #lockTtlSeconds} 须按 {@code 读超时 × attempts} 同步抬高。</p>
     */
    private boolean unparseableRetryEnabled = true;

    /**
     * 是否启用核验判定静态校验器（`RV-1`，<b>Warn 档</b>）。默认 true。
     *
     * <p>开启后只把校验器的 finding 写进 timeline 事件（{@code sub_task_auto_review_verdict_warn}），
     * <b>不改变判定结果与状态流转</b>；关闭即回到未启用时的行为（零观测事件）。
     * 强制档（`Block` 落地为 {@code INCONCLUSIVE}）属 `RV-2`，不在本开关内。</p>
     */
    private boolean verdictValidatorEnabled = true;

    /**
     * 是否启用静态覆盖批评器（`RV-3`）。默认 true。
     *
     * <p>与 {@link #verdictValidatorEnabled} 分开配：后者管判定形态自洽（`RV-1`），本开关管「通过是否有逐条覆盖声明」，
     * 新增 {@code Block} 来源（{@code coverage_absent}/{@code acceptance_not_covered}/{@code unconfirmed_silently_dropped}）
     * 直接产出 {@code INCONCLUSIVE} 转人工，影响面不同量级需单独回滚。关闭后 {@code coverage} 仍解析落库（纯观测）但不参与判定；
     * 若模型大面积不填 {@code coverage} 致通过全转人工，先关本开关再改提示词，不要放宽覆盖判据（一放宽就没有拦截面）。</p>
     */
    private boolean coverageCriticEnabled = true;

    /**
     * 是否启用交付正文编码损坏检测（`RV-7`，<b>Warn 档</b>）。默认 true。
     *
     * <p>开启后对「执行产出 + 物化附件正文」做确定性检测（U+FFFD / 私用区字符 /
     * GBK·Windows-1252 双重解码回推），命中只并入 {@code sub_task_auto_review_verdict_warn}
     * 事件，<b>不改变判定</b>。与 {@link #verdictValidatorEnabled} 独立：关闭判定校验器
     * 不影响编码观测，反之亦然。</p>
     */
    private boolean encodingDetectionEnabled = true;

    /** 是否启用 Reviewer 抽检任务。默认 true。 */
    private boolean recheckEnabled = true;

    /**
     * 是否启用收尾轮自审闸门（`SR-3`）。默认 true。
     *
     * <p>关闭后的回滚面是<b>三项行为</b>：① 收尾轮不注入 {@code internal_run_in_sandbox}
     * （回到「收尾轮只产文本」）；② 不判闸门；③ 不因证据不合格重跑。围栏文案里的
     * {@code EVIDENCE} 段<b>保留</b>（模型未执行时按指令填 {@code []}，无副作用），
     * 证据核对事件 {@code self_review_evidence_checked} 也<b>保留</b>——纯观测不参与判定，
     * 关掉它会让「自审到底跑没跑」重新变成不可度量。</p>
     */
    private boolean selfReviewEnabled = true;

    /**
     * 自审闸门不合格时的<b>内容重跑</b>轮数上限（{@code K}，`SR-3`）。默认 1。
     *
     * <p><b>与网络重试的乘数关系（改前必读，否则会把成本调爆）</b>：本值是「证据不合格重跑」的
     * 外层轮数；收尾轮自身还有 {@code FinalReportFenceHook.FINALIZE_MAX_ATTEMPTS = 2} 的
     * <b>网络 / 429 内层重试</b>。单次执行最坏模型调用数 = {@code 内层 2 × (1 + K)}，
     * 默认 K=1 ⇒ <b>最坏 4 次</b>。该乘数已在 {@code FinalReportFenceHookTest} 以断言锁定。</p>
     */
    private int selfReviewMaxRetry = 1;

    /** 抽检扫描周期（毫秒）。默认 1 小时。 */
    private long recheckIntervalMs = 3_600_000;

    /** 单轮抽检抽样比例（对窗口内 APPROVED + 有快照的 REJECTED 合池存量）。默认 0.05（5%）。 */
    private double recheckSampleRatio = 0.05;

    /** 单轮抽检批量上限（防单轮 LLM 调用过多阻塞调度）。默认 20。 */
    private int recheckMaxBatch = 20;

    /** 抽检时间窗口（天）：只抽近 N 天内落库的 APPROVED / 有快照 REJECTED 记录。默认 7。 */
    private int recheckWindowDays = 7;

    /**
     * 审核证据自动物化护栏（`RV-15`，配置键 {@code helloai.review.evidence-materialization.*}）。
     *
     * <p>物化把「受审附件从对象存储搬到 reviewer 沙箱」升为平台承诺（取代 RV-14 模型命令式 stage——工具可用 ≠ 模型会用），
     * 但有界是承诺的一部分：数量/单文件/总量三闸任一触顶即停，剩余转 {@code deferred} 由模型按需 stage 补拉，
     * 而非把整个任务全部附件无界搬进容器。{@code maxFiles} 取 40 覆盖 35 附件批回放（`G-034`）并留余量，
     * 上限过小会让近半批次退回 RV-14 不可靠的手动补拉；真正的物理闸是字节（{@code /tmp} 容量）。</p>
     */
    private EvidenceMaterialization evidenceMaterialization = new EvidenceMaterialization();

    /** 审核证据自动物化配置（{@code helloai.review.evidence-materialization.*}）。 */
    @Data
    public static class EvidenceMaterialization {

        /** 是否启用自动物化。默认 <b>true</b>。关闭 ⇒ 回到 RV-14 手动 stage 通道（模型命令式拉取）。 */
        private boolean enabled = true;

        /**
         * 单次物化<b>文件数</b>上限。默认 <b>40</b>（覆盖 35 附件批并留余量）。
         * 达到后剩余附件全部转 {@code deferred}，由模型凭证据清单按需 stage 补拉。
         */
        private int maxFiles = 40;

        /**
         * 单文件大小上限（字节）。默认 <b>5MB</b>（{@code 5 * 1024 * 1024}）。
         * 超过的附件即使 {@code contentLoadable=true} 也不下载，按 {@code too_large} 跳过
         * （先看台账快照大小，不产生实际读取）。
         */
        private long maxBytesPerFile = 5L * 1024 * 1024;

        /**
         * 单批物化字节总量上限（字节）。默认 <b>50MB</b>（{@code 50 * 1024 * 1024}）。
         * 累计触顶后剩余附件全部转 {@code deferred}（总量是 `/tmp` 容量的真实闸）。
         */
        private long maxTotalBytes = 50L * 1024 * 1024;
    }
}
