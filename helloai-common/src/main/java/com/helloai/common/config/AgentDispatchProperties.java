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
     * V24：子任务重分配最大尝试次数（熔断阈值）。
     *
     * <p>所有类型的重分配（离线重派、超时回收、N11回退、阻塞重试）
     * 每尝试一次累加 sub_task.reassign_attempt_count；达到本阈值后不再
     * 重新分配，直接标记子任务为 CANCELLED，打破无限重试死循环。</p>
     *
     * <p>默认 5 次。设为 0 或负数表示禁用熔断（不推荐生产使用）。</p>
     */
    private int maxReassignAttempts = 5;

    /**
     * V25：ASSIGNED 超时未 claim 回收阈值（分钟）。
     *
     * <p>子任务 ASSIGNED 后若 update_time 超过本阈值仍无人 claim，
     * 由 AssignedSubTaskTimeoutTask 回收到 PENDING 并重新进入调度链。
     * 原为硬编码常量 10 分钟，现提为配置项便于联调时缩短观察周期。</p>
     *
     * <p>默认 10 分钟。</p>
     */
    private int assignedTimeoutMinutes = 10;

    /**
     * V27：是否启用子任务提交后的 LLM 自动核验（REVIEW 门控）。
     *
     * <p>开启后执行成功提交（→REVIEW）会在事务提交后异步触发
     * SubTaskReviewService 按验收标准自动判定；关闭后子任务停留 REVIEW
     * 等人工审查（行为与现状一致）。默认 true。</p>
     */
    private boolean autoReviewEnabled = true;

    /**
     * V27：自动核验驳回次数上限（沿用 sub_task.rework_count 计数）。
     *
     * <p>reworkCount 达到本阈值后自动核验不再打回，子任务停留 REVIEW
     * 等人工处理，避免"执行→驳回→重执行"无限循环。默认 3 次。</p>
     */
    private int autoReviewMaxRework = 3;

    // 注：原 heartbeatFreshMinutes 字段（v2.6 §4.1 2026-07-20）已迁移至
    //     AgentHealthProperties.offlineMinutes，作为 Selector / Reconcile / SQL 回退候选
    //     共用的单一心跳阈值来源。详见 com.helloai.common.config.AgentHealthProperties。
}
