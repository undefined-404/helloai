package com.helloai.common.config;

import com.helloai.common.constant.AgentAccessType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调度分配策略配置。
 *
 * <p>该配置只影响“候选选择与排序”，不改变执行链（ExecutionCommand/Poller/MCP）本身。
 * 主要用于支持“外部优先 + 空闲优先 + 平台内 LLM 保底”的目标态，并提供可控的回归开关。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.dispatch")
public class AgentDispatchProperties {
    /**
     * 是否在同角色候选中优先外部 Agent（CLI_CLIENT）。
     *
     * <p>默认 false，避免影响纯 LLM 保底链路回归。</p>
     */
    private boolean preferExternal = false;

    /**
     * 选人策略（默认 {@link SelectionStrategy#SCORE_DESC}）。
     *
     * <p>不采用「最少连接（WLC）」：{@code require-idle=true} 已过滤非空闲候选，池内在飞数恒 0，
     * WLC 退化为纯权重比较；串行公平性靠「按权重轮换」（{@link SelectionStrategy#WEIGHTED_RANDOM}）而非瞬时负载。</p>
     */
    private SelectionStrategy selectionStrategy = SelectionStrategy.SCORE_DESC;

    /** 加权随机权重下限（score 最差的候选）：保证差模型仍有相对机会，不被永久饿死。 */
    private double weightMin = 1.0;

    /** 加权随机权重上限（score 最好的候选）。 */
    private double weightMax = 5.0;

    /** 选人策略枚举。 */
    public enum SelectionStrategy {
        /** 按 score 降序取最高（原行为）。 */
        SCORE_DESC,
        /** 加权随机（按 score 权重抽签，含在飞负载惩罚；无状态、分布式安全）。 */
        WEIGHTED_RANDOM
    }

    /**
     * 是否要求候选 Agent 当前无执行中任务。
     *
     * <p>默认 true。执行中任务以 {@code sub_task.status=IN_PROGRESS} 统计。</p>
     */
    private boolean requireIdle = true;

    /**
     * E2：是否强制并发额度（maxConcurrent 派发即占用）。
     *
     * <p>默认 true。开启后：
     * <ul>
     *   <li>选人链：当前占用 &gt;= 额度的 Agent 被跳过（{@code AgentSelector}）</li>
     *   <li>落库前：{@code SubTaskService.assignNext} 锁 agent 行后重新判定，超发直接拒派</li>
     * </ul>
     * 额度来源：ACTIVE 值班租约 maxConcurrent；无租约时仅 capabilities 显式声明
     * {@code maxConcurrentTasks} 才约束（未声明不限制，与 E2 前行为一致）。</p>
     */
    private boolean enforceMaxConcurrent = true;

    /**
     * 强制仅在指定接入类型内选人（回归/演练开关）。
     *
     * <p>典型用法：设置为 {@code API_KEY_LLM}，可实现“纯 LLM 保底测试”不被外部 Agent 抢占。</p>
     */
    private AgentAccessType forceAccessType;

    /**
     * 创建子任务时是否自动分配（初始分配）。
     *
     * <p>默认 false，保持现有"创建即 PENDING，可由外部 Agent claim"的工作流不变；
     * 打开后将调用调度服务按角色自动选人并进入 ASSIGNED。</p>
     */
    private boolean autoAssignOnCreate = false;

    /**
     * 子任务重分配最大尝试次数（熔断阈值）。
     *
     * <p>所有类型的重分配（离线重派、超时回收、N11回退、阻塞重试）
     * 每尝试一次累加 sub_task.reassign_attempt_count；达到本阈值后不再
     * 重新分配，转入 DEAD_LETTER 死信池待人工兜底（原为 CANCELLED），
     * 打破无限重试死循环。</p>
     *
     * <p>默认 5 次。设为 0 或负数表示禁用熔断（不推荐生产使用）。</p>
     */
    private int maxReassignAttempts = 5;

    /**
     * 「长期无候选 → 人工介入」的最大连续轮数（时钟 C）。
     *
     * <p><b>与 {@link #maxReassignAttempts} 语义不同</b>：那个统计的是「<b>试过但没派成</b>」
     * 的重派次数（选出人后被退回/超时/离线等）；本项统计的是「<b>根本没试成</b>」——
     * 当前压根没有可用候选执行 Agent（外部 Agent 忙 / 离线 / 单会话已被占用），
     * 属可自愈的等待态，不消耗重派预算（见 P1-1 的 {@code NoCandidateAgentException}）。</p>
     *
     * <p>连续 {@code 无候选} 达到本阈值（每轮由孤儿巡检按 {@link #noCandidateRetryIntervalSeconds}
     * 节拍累加）即判定为「长期无候选」，转人工介入（{@code no_candidate_long_wait}），
     * 停止自动重试等人工处置。默认 10。设为 0 或负数表示禁用该兜底（永远自动重试）。</p>
     */
    private int noCandidateMaxRounds = 10;

    /**
     * 无候选后的重试间隔基准（秒，时钟 C）。
     *
     * <p>每次「无候选」失败后，为该子任务写入 {@code context.noCandidate.nextDispatchAt}
     * = now + 本值 + 抖动；孤儿巡检（时钟 A，60s 一跳）在 {@code now < nextDispatchAt}
     * 时跳过该子任务，从而把「每 60s 空扫一次」改造为「每子任务独立 120s 节拍」，
     * 且各子任务的倒计时天然各不相同（前端可显示「约 N 秒后重试」）。默认 120。</p>
     */
    private int noCandidateRetryIntervalSeconds = 120;

    /**
     * 无候选重试节拍的抖动上限（秒，时钟 C）。
     *
     * <p>{@code nextDispatchAt = now + (interval + rand[0, jitter])}，用于打散同批次
     * 子任务的重试时刻，避免它们在同一个 tick 上同步惊群（同时抢同一批候选）。
     * 默认 15；设为 0 表示不抖动。</p>
     */
    private int noCandidateRetryJitterSeconds = 15;

    /**
     * ASSIGNED 超时未 claim 回收阈值（分钟）。
     *
     * <p>子任务 ASSIGNED 后若 update_time 超过本阈值仍无人 claim，
     * 由 AssignedSubTaskTimeoutTask 回收到 PENDING 并重新进入调度链。
     * 原为硬编码常量 10 分钟，现提为配置项便于联调时缩短观察周期。</p>
     *
     * <p>默认 10 分钟。</p>
     */
    private int assignedTimeoutMinutes = 10;

    /**
     * 是否启用子任务提交后的 LLM 自动核验（REVIEW 门控）。
     *
     * <p>开启后执行成功提交（→REVIEW）会在事务提交后异步触发
     * SubTaskReviewService 按验收标准自动判定；关闭后子任务停留 REVIEW
     * 等人工审查（行为与现状一致）。默认 true。</p>
     */
    private boolean autoReviewEnabled = true;

    /**
     * 自动核验驳回次数上限（沿用 sub_task.rework_count 计数）。
     *
     * <p>reworkCount 达到本阈值后自动核验不再打回，子任务停留 REVIEW
     * 等人工处理，避免"执行→驳回→重执行"无限循环。默认 3 次。</p>
     */
    private int autoReviewMaxRework = 3;

    /**
     * 自动核验重复失败短路开关（P-1 防御层 A2-2）。
     *
     * <p>「本轮驳回 issues 与上一轮相似度 ≥ {@link #autoReviewRepeatFailureSimilarity}」<b>且</b>
     * 「评分未提升」<b>同时成立</b>时判结构性失败，不再返工重派、直接转 DEAD_LETTER 待人工。
     * 单靠评分停滞会误伤「问题已变、有实质进展」的返工，故用双条件；真停滞由返工预算
     * （{@link #autoReviewMaxRework} / attempt_total）兜底封顶。默认 true。</p>
     */
    private boolean autoReviewRepeatFailureShortCircuit = true;

    /**
     * 重复失败相似度阈值（P-1 防御层 A2-2）：连续两轮驳回 issues 的字符 bigram Jaccard 相似度，
     * 1.0 = 完全相同。默认 0.85——容忍措辞微漂移又要求实质同因；与「评分未提升」联合判定（同时成立才短路）。
     */
    private double autoReviewRepeatFailureSimilarity = 0.85;

    /**
     * `LOG-20261011-007` B 收敛熔断开关：「执行者自报未完成连续 N 轮」提前熔断
     * （`D-2026-10-12-3` 落地形态，判据口径 `LOG-20261012-008` 决策 3）。
     *
     * <p>补 {@link #autoReviewRepeatFailureShortCircuit} 双条件的盲区：真停滞（评分恒定 + 驳回意见整篇重写）
     * 全文相似度低于 {@link #autoReviewRepeatFailureSimilarity} 阈值，不被短路。文本相似度路已被真实语料证伪
     * （换 {@code [defect]} 标签比较更低），故改用句法级零阈值信号：执行者轮报 {@code VERIFICATION:} 围栏的
     * {@code STATUS}（`REF-8.2.2` 契约）——连续 N 轮 {@code BLOCKED}/{@code PARTIAL} 且评分未严格提升
     * ⇒ 判结构性做不到，早于 {@link #autoReviewMaxRework} 转人工并标注「无能力满足 / 输入缺失」。
     * 轮数见 {@link #autoReviewSelfStallRounds}。默认 true。</p>
     */
    private boolean autoReviewSelfStallEnabled = true;

    /**
     * 「执行者自报未完成连续 N 轮」的 N（`LOG-20261011-007` B / `D-2026-10-12-3`）。
     *
     * <p>默认 3；取 1 或 0 视为禁用（至少两轮才谈得上「连续」）。计数依据是执行者显式自报状态
     * （句法级、无需阈值标定），非靠模糊文本比较的驳回原因。</p>
     */
    private int autoReviewSelfStallRounds = 3;

    /**
     * §6.52：N11 外部回退时是否跳过"执行密集"任务的自动降级。
     *
     * <p>执行密集任务（需本机 shell/文件/服务操作）回退给无本机能力的 API_KEY_LLM
     * 会导致交付物永远不达标、返工循环、最终卡死审核；开启后此类任务不自动回退，
     * 停留原状态并标记人工介入（用户在前端自主选择 agent 改派）。默认 true。</p>
     */
    private boolean fallbackSkipExecutionDense = true;

    /**
     * `LOG-20261011-007` A 能力闸：「需读原始附件」任务的无能力派发拦截。
     *
     * <p>与 {@link #fallbackSkipExecutionDense} 同族但独立可关：前者治「需在本机跑东西」（脚本/容器/服务），
     * 本项治「需打开用户给的结构化文件本体逐项核对」（xlsx/docx/pdf；{@code internal_read_attachment}
     * 只做 UTF-8 解码，读不出二进制列）。独立开关因本项靠关键词共现判定
     * （{@code SubTaskDispatchService.ATTACHMENT_SOURCE_PATTERN} × {@code ATTACHMENT_VERIFY_PATTERN}），
     * 误拦风险高于已真机验证的执行密集闸。命中后动作与执行密集闸一致（共用
     * {@code sub_task_dispatch_skip_no_capability} 事件与 {@code dispatch_skip_execution_dense} 人工介入标记）：
     * fast-fail 走 fallback 选同角色替代者，无替代时停留 PENDING + 人工介入。默认 true。</p>
     */
    private boolean attachmentReadSkipEnabled = true;

    /**
     * 兜底：REVIEW 孤儿扫描阈值（秒）。
     *
     * <p>子任务进入 REVIEW 超过此秒数<b>且未标记人工介入</b>时，SubTaskReviewService 的 @Scheduled
     * 兜底扫描触发核验，作为 AFTER_COMMIT 事件链丢失时的二次确保。默认 60s。
     * 候选不按 {@code review_record} 是否存在过滤（通过/驳回后已离开 REVIEW）；已转人工
     * （{@code INCONCLUSIVE}/返工达上限/自相矛盾）打 {@code context.manualIntervention} 标记永久退出扫描。</p>
     */
    private int reviewOrphanThresholdSeconds = 60;

    /**
     * 兜底：REVIEW 孤儿扫描每批上限。默认 10。</p>
     */
    private int reviewOrphanBatchSize = 10;

    /**
     * 质量画像回灌权重（反馈回路第 1 层）。
     *
     * <p>用于两处调度回灌（同源配置，均可通过置 0 关闭回退）：
     * <ul>
     *   <li>{@code AgentSelector} 选人排序：qualityRank（画像质量分归一化档位）
     *       乘以本权重后插入 dutyRank 之后参与比较，低权重起步防抖动</li>
     *   <li>{@code AgentDutyLeaseServiceImpl.resolveTtlMinutes} 动态 TTL 复合分：
     *       performanceScore = 失败折算分 + 质量分(0~100) × qualityWeight</li>
     * </ul>
     * 质量画像缺失时两处均回退原逻辑（best-effort，不阻断调度主链路）。
     * 默认 0.1；0 表示完全关闭质量分参与（与画像回灌前行为一致）。</p>
     */
    private double qualityWeight = 0.1;

    /**
     * 成本画像回灌权重（B5.3 Fleet 成本选人）。
     *
     * <p>{@code AgentSelector} 选人排序：costRank（最近 {@link #costSampleLimit} 次成功执行 token 均值，
     * min-max 反向归一 1~5 档，最省=5；无数据记中立档；有效样本 &lt; 2 时该维度不生效）乘本权重，
     * 插入 qualityRank 之后、score 之前。比较器是字典序链
     * {@code dutyRank → accessTypeRank → qualityRank×w1 → costRank×w2 → score}（非加权求和），
     * 故 qualityRank 档位不同时 costRank 不生效，成本/质量只在同值班档内起作用。
     * 默认 0.1；0 表示关闭成本分参与排序。</p>
     */
    private double costWeight = 0.1;

    /**
     * 成本画像取样条数：最近 N 次<b>成功执行</b>的 token 均值（B5.3）。
     *
     * <p>数据源 {@code agent_execution_record}（{@code status='SUCCESS'} 且
     * {@code token_usage IS NOT NULL}，按 {@code id DESC} 取最近 N 条）。</p>
     *
     * <p>默认 5——对近期模型/负载变化敏感，同时保证样本量可控；
     * 外部执行者的 token 由 MCP {@code submitResult} 可选参数回报（B5.1）。</p>
     */
    private int costSampleLimit = 5;

    /**
     * 自动核验证据硬检查的附件补偿等待（毫秒）。
     *
     * <p>产出物化在结果回报事务 afterCommit 同步执行，自动核验在 AFTER_COMMIT
     * 异步线程启动，两者存在毫秒级竞态；执行密集任务证据检查未发现可读附件时
     * 先等待本窗口再重查一次，避免物化未完成被误判为无证据。默认 1000ms，
     * 0 表示不等待（测试/联调可关闭）。</p>
     */
    private int reviewEvidenceCheckWaitMs = 1000;

    /**
     * 核验侧附件内容注入开关（方案3 F2）：开启后核验 Prompt 注入可直读物化附件正文
     * （每附件 64000 字符、总计 200000 字符截断），Reviewer 基于真实文件内容核对
     * "声称交付物 ↔ 文件正文 ↔ 验收标准"；关闭时仅注入附件清单。默认 true。
     */
    private boolean attachmentContentEnabled = true;

    /**
     * 任务自动收口（全部子任务 DONE/CANCELLED → Task DONE）后，
     * 是否异步触发 Planner 生成最终整合报告。
     *
     * <p>关闭后仍可通过 {@code POST /api/tasks/{id}/final-report} 手动生成。
     * 默认 true。</p>
     */
    private boolean autoFinalReportEnabled = true;

    /**
     * 最终整合报告大纲先行两段式开关（规划 → 成文）。
     *
     * <p>开启后生成主链先做一次归并出纲调用（覆盖追溯表 / 主线论点 / 章节顺序 / 矛盾清单），
     * 再按大纲章节分片渲染正文；关闭或大纲调用失败/解析失败时降级为单次调用
     * （与开关引入前行为一致）。默认 true。</p>
     */
    private boolean autoFinalReportOutlineEnabled = true;

    /**
     * 最终整合报告自动质量审查开关（3A 闭环）。
     *
     * <p>开启后报告生成成功即触发一次 LLM 质量审查（自审自过硬守卫：审查人不得是
     * 报告写作者，否则跳过审查并落 review_skipped 标记）；驳回且未达返工上限时
     * 自动 rework 重写。默认 true。</p>
     */
    private boolean autoFinalReportReviewEnabled = true;

    /**
     * 最终整合报告驳回返工轮次上限（3A 闭环）。
     *
     * <p>审查驳回触发 rework 的最大轮数（首次生成不算轮数）：达到上限后不再自动重写，
     * 当前报告保留并落 max_review_reached 标记，等待人工/手动重生成。默认 1；
     * 设为 0 表示驳回后不返工（报告保留现状）。</p>
     */
    private int autoFinalReportMaxReview = 1;

    /**
     * 最终报告审查孤儿兜底阈值（秒，§12.2 审查链三级容错 L3）。
     *
     * <p>报告停留 {@code REVIEWING} 且 {@code final_report_time} 早于 {@code now - 本阈值} 时，
     * 判定审查触发链（L1 事件 / L2 Outbox→MQ）全部丢失，由 {@code FinalReportReviewOrphanTask}
     * 收敛为 {@code DONE} 并落 {@code task_final_report_review_orphan_converged} 审计事件。
     * 收敛 DONE 而非重投：报告正文早已落库交付，重投会在审查持续失败时反复烧 LLM 配额，需补跑可走 UI「重新生成」。
     * 默认 660 必须 ≥ 防双审锁租期（{@code FinalReportReviewLock.TTL_SECONDS}=600s），
     * 否则把在途慢审查误判为孤儿（§12.5 #3）。</p>
     */
    private int finalReportReviewOrphanThresholdSeconds = 660;

    /**
     * 最终报告审查孤儿兜底单轮批量上限（§12.2 L3）。默认 20。
     */
    private int finalReportReviewOrphanBatchSize = 20;

    // 心跳阈值统一见 AgentHealthProperties.offlineMinutes（Selector / Reconcile / SQL 回退共用单一来源）。
}
