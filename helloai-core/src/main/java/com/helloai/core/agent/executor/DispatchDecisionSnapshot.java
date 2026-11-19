package com.helloai.core.agent.executor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 派单决策快照（`MA-2`）：一次「选人」当时的候选池与各自位次，供事后复盘。
 *
 * <p><b>解决什么问题</b>：此前「这单为什么派给了他」只能翻日志猜——选人器内部
 * 早已算出质量档、成本档、在飞数、档位，但方法结束只 {@code return} 一个
 * {@link com.helloai.core.agent.entity.Agent}，其余中间量全部丢弃。本快照只做一件事：
 * 把<b>已经算好的东西</b>原样留下来，<b>不新增任何判据、不改权重、不换比较器</b>
 * （改判据就说不清行为变化是快照引起的还是新判据引起的）。</p>
 *
 * <p><b>形状与归属</b>：纯数据 record，落在 {@code agent} 域（选人器所在域）。
 * 它<b>不落库</b>——选人器在 {@code agent} 域、{@code sub_task.context} 在 {@code task} 域，
 * 跨域直写是「直捅」；落库由 {@code task} 域的 {@code SubTaskDispatchServiceImpl} 负责。</p>
 *
 * <p><b>★ 硬约束：只放投影字段</b>。{@code model} / {@code apiKey} / {@code prompt} /
 * {@code endpoint} 等敏感配置<b>一律不入</b>——快照会进 JSONB 并被接口下发，
 * 与 {@code SandboxCallLedger}「只记前缀」同一套安全口径。</p>
 *
 * <p><b>覆盖式语义</b>：每次派单覆盖写一份（按 {@code attempt} 递增），不是全量历史。
 * 要全量历史必须建表，属另一批。</p>
 *
 * @param attempt        本次是第几次派单尝试；取自 {@code sub_task.attempt_total}
 *                       （选人器不知道、由落库侧回填，故选人器产出时为 {@code null}）
 * @param path           {@link #PATH_PRIMARY}（选人器选人）或 {@link #PATH_FALLBACK}（N11 阈值回退）
 * @param decidedAt      决策时刻（ISO-8601 字符串，便于与时间线事件对齐；不参与身份）
 * @param candidateTotal 过滤后候选池的<b>真实总数</b>（截断不影响它）
 * @param truncated      候选是否被截断（{@code candidateTotal > }{@link #MAX_CANDIDATES}）
 * @param candidates     候选明细（至多 {@link #MAX_CANDIDATES} 条，按最终比较器降序）
 */
public record DispatchDecisionSnapshot(
        Integer attempt,
        String path,
        String decidedAt,
        int candidateTotal,
        boolean truncated,
        List<Candidate> candidates) {

    /** 选人器选人（分档 + 多维比较）。 */
    public static final String PATH_PRIMARY = "primary";

    /** N11 阈值回退（单维 {@code max(score)}，与主路径口径不同，故单列）。 */
    public static final String PATH_FALLBACK = "fallback";

    /**
     * 候选明细上限。
     *
     * <p>上百 Agent 的候选池全存会让单行 JSONB 失控；而「总数」这个信息不能丢，
     * 故与 {@link #candidateTotal()} / {@link #truncated()} 三件套一起使用。</p>
     */
    public static final int MAX_CANDIDATES = 10;

    /**
     * 单个候选的位次投影。
     *
     * <p><b>缺失语义（必须与「最差值」区分）</b>：{@code null} = 该维度<b>本次未参与</b>
     * （质量 / 成本权重关闭、候选内有效样本不足、或回退路径根本没有排名），
     * 与「排第 0 档」不是一回事——编造 0 会让人误读成「最差」。
     * {@code inFlight} 为 {@code null} 的额外原因见 {@link DispatchDecisionSnapshot} 的实现说明：
     * {@code SCORE_DESC} 策略不采集在飞数（只有加权随机需要它做分母），故不记而不编 0。</p>
     *
     * @param agentId     Agent ID
     * @param name        Agent 名称（人读定位用；改名后与历史快照可能不一致，属快照固有语义）
     * @param role        角色名（枚举名，不存枚举本身以免 JSONB 结构随枚举演化）
     * @param score       排序用的 {@code agent.score}（可空）
     * @param qualityRank 质量档位 0~5；{@code null} = 质量维度未参与
     * @param costRank    成本档位 1~5（5 = 最省）；{@code null} = 成本维度未参与
     * @param inFlight    选人时该 Agent 的在飞任务数；{@code null} = 本次未采集
     * @param tier        档位序号（0 = 最优档，按「值班 → 接入类型 → 质量 → 成本」分组）；
     *                    {@code null} = 回退路径（单维 {@code max(score)}，没有分档概念）
     * @param winner      是否为中选者（一次快照内唯一）
     */
    public record Candidate(
            Long agentId,
            String name,
            String role,
            Integer score,
            Integer qualityRank,
            Integer costRank,
            Integer inFlight,
            Integer tier,
            boolean winner) {
    }

    /**
     * 主路径快照（选人器产出的候选已按最终比较器降序，这里只做截断与计数）。
     *
     * @param ranked 按最终比较器降序的候选明细（未截断）
     * @return 快照（{@code attempt} 待落库侧回填）
     */
    public static DispatchDecisionSnapshot primary(List<Candidate> ranked) {
        return of(PATH_PRIMARY, ranked);
    }

    /**
     * 回退路径快照。
     *
     * @param ranked 按 {@code score} 降序的候选明细（未截断）
     * @return 快照（{@code path=fallback}，排名字段为 {@code null}）
     */
    public static DispatchDecisionSnapshot fallback(List<Candidate> ranked) {
        return of(PATH_FALLBACK, ranked);
    }

    private static DispatchDecisionSnapshot of(String path, List<Candidate> ranked) {
        List<Candidate> all = ranked == null ? List.of() : ranked;
        List<Candidate> kept = all.size() <= MAX_CANDIDATES
                ? List.copyOf(all) : List.copyOf(all.subList(0, MAX_CANDIDATES));
        return new DispatchDecisionSnapshot(null, path, OffsetDateTime.now().toString(),
                all.size(), all.size() > MAX_CANDIDATES, kept);
    }

    /**
     * 回填 {@code attempt}（落库侧唯一写点，取自 {@code sub_task.attempt_total}）。
     *
     * <p>选人器不知道这是第几次派单，{@code attempt_total} 也不在它的视野里；
     * 由 {@code task} 域在「派单成功之后」读库回填，避免同一事实两个来源。</p>
     *
     * @param attemptTotal {@code sub_task.attempt_total}（可空）
     * @return 回填后的快照（不可变 record，原对象不变）
     */
    public DispatchDecisionSnapshot withAttempt(Integer attemptTotal) {
        return new DispatchDecisionSnapshot(attemptTotal, path, decidedAt, candidateTotal,
                truncated, candidates);
    }
}
