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
     * §6.52：N11 外部回退时是否跳过"执行密集"任务的自动降级。
     *
     * <p>执行密集任务（需本机 shell/文件/服务操作）回退给无本机能力的 API_KEY_LLM
     * 会导致交付物永远不达标、返工循环、最终卡死审核；开启后此类任务不自动回退，
     * 停留原状态并标记人工介入（用户在前端自主选择 agent 改派）。默认 true。</p>
     */
    private boolean fallbackSkipExecutionDense = true;

    /**
     * 兜底：REVIEW 孤儿扫描阈值（秒）。
     *
     * <p>子任务进入 REVIEW 超过此秒数且无 review_record 时，
     * SubTaskReviewService 的 @Scheduled 兜底扫描会触发核验。
     * 作为 AFTER_COMMIT 事件链丢失时的二次确保。默认 60s。</p>
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
     * （每附件 8000 字符、总计 24000 字符截断），Reviewer 基于真实文件内容核对
     * "声称交付物 ↔ 文件正文 ↔ 验收标准"；关闭时仅注入附件清单（与开关引入前行为一致）。
     * 默认 true。
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

    // 注：原 heartbeatFreshMinutes 字段已迁移至
    //     AgentHealthProperties.offlineMinutes，作为 Selector / Reconcile / SQL 回退候选
    //     共用的单一心跳阈值来源。详见 com.helloai.common.config.AgentHealthProperties。
}
