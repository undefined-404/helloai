package com.helloai.core.agent.service;

import com.helloai.core.task.entity.Uncertainty;
import lombok.Data;

import java.util.List;

/**
 * MCP 工具核心逻辑。
 * 每个方法对应一个 MCP 工具，供 Controller（REST + JSON-RPC）统一调用。
 */
public interface McpToolService {

    /** 拉取收件箱消息（含子任务通知摘要与已读状态）。 */
    PullTasksResult pullTasks(Long agentId, String role, int max);

    /** 拉取收件箱消息（includeRead=true 时返回已读消息）。 */
    PullTasksResult pullTasks(Long agentId, String role, int max, boolean includeRead);

    /** 确认收件箱消息（幂等）。 */
    AckResult ack(Long agentId, String messageId);

    /** 认领子任务（乐观锁防并发）。 */
    ClaimSubTaskResult claimSubTask(Long agentId, Long subTaskId);

    /** 心跳（含值班租约状态）。 */
    HeartbeatResult heartbeat(Long agentId);

    /** 上传产物附件登记。 */
    UploadArtifactResult uploadArtifact(Long agentId, Long subTaskId,
                                        String fileName, String mimeType,
                                        Long fileSize, String storageUrl);

    /** 提交子任务执行结果（幂等）。 */
    SubmitResultResult submitResult(Long agentId, Long subTaskId, String resultId,
                                    Boolean success, String output, String error, String finishReason);

    /** 上报子任务阻塞。 */
    ReportBlockedResult reportBlocked(Long agentId, Long subTaskId, String reason);

    /** Agent 签到（签发值班租约）。 */
    CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes);

    /**
     * Agent 签到（签发值班租约）+ 上报已加载技能列表（P2 §6.115）。
     *
     * <p>{@code reportedSkills} 与既有 {@code agent.skills} 取并集（归一化后只增不减），
     * 合并结果经 {@link CheckInResult#getMergedSkills()} 回显；合并失败不阻断打卡。</p>
     */
    CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes,
                          List<String> reportedSkills);

    /** Agent 签退（结束值班租约）。 */
    CheckOutResult checkOut(Long agentId, String reason);

    /** 查询 Agent 实时状态（DB 持久值 + 实时推算）。 */
    GetAgentStatusResult getAgentStatus(Long agentId);

    /** 汇总前置子任务产出内容（物化附件优先，回退执行记录输出）。 */
    GetDepsSummaryResult getDepsSummary(Long agentId, Long subTaskId);

    /**
     * 查询子任务详情（执行内容 / 交付物 / 验收标准 / 执行约束 / 不确定性申报）。
     *
     * <p>「验收标准下发」缺口补齐：外部执行者经 MCP 此前只能看到收件箱摘要（仅交付物），
     * 但审查侧按 {@code acceptance} 核验——本工具把执行者据此开工所需的子任务全文
     * 一次性下发，与平台内执行 Prompt（SubTaskExecutionServiceImpl.buildUserPrompt）
     * 的「当前任务」四要素同源。授权：仅当前已分配给本 Agent 的子任务，
     * 或尚未分配且状态为 PENDING（可认领）的子任务可查。</p>
     */
    SubTaskDetail getSubTaskDetail(Long agentId, Long subTaskId);

    // ================================================================
    // result DTOs (used by Controller to serialize)
    // ================================================================

    @Data
    class PullTasksResult {
        private List<Message> messages;

        @Data
        public static class Message {
            private String messageId;
            private String type;
            private Long subTaskId;
            private Long taskId;
            private String title;
            private String priority;
            private String deadline;
            /** 收件箱通知摘要（sub_task.rejected/approved 携带 review 评分与评语摘要）。 */
            private String summary;
            /** 未读/已读状态位（false=未读待 ack，true=已 ack；includeRead=true 时才可能为 true）。 */
            private Boolean read;
            /** 消息对应子任务已转移给其他 Agent（当前执行者非本 Agent）。 */
            private Boolean reassigned;
            /** 子任务当前实际执行者 Agent ID（配合 reassigned 使用）。 */
            private Long currentAgentId;
        }
    }

    @Data
    class AckResult {
        private boolean ok;
        private boolean acknowledged;
        private String messageId;
    }

    @Data
    class ClaimSubTaskResult {
        private boolean ok;
        private boolean claimed;
        private String reason;
        private Long assignedAgent;
        private Long subTaskId;
        private Integer version;
        /** 认领成功（claimed=true）时携带子任务详情，供执行者立即开工，无需再调 getSubTaskDetail。 */
        private SubTaskDetail detail;
    }

    /**
     * 子任务详情（getSubTaskDetail 返回体 / ClaimSubTaskResult.detail）。
     *
     * <p>字段与子任务实体一一对应，不做事后截断：验收标准与执行边界被截断正是
     * 「执行者看不到验收标准」缺口的成因，故全文下发（内容长度由拆解侧 LLM 产物约束）。</p>
     *
     * <p>§6.1 豁免记录（2026-09-11，code review 显式豁免）：{@code uncertainties} 字段
     * 直接使用 task 域 {@link Uncertainty} 值对象，使本接口新增 1 处 agent → task entity
     * 类型引用（此前本文件无 task 域 import）。豁免理由：该字段为只读投影，kind 常量
     * （ASSUMPTION / UNCONFIRMED）语义必须与 task 域执行注入、审查核验链保持单源，
     * 另建同形值对象将引入双源漂移；本引用不注入 task 域 Service、不产生任何写操作。
     * 回收方向：随 §6.1 AgentRuntime 改造（Port 反转 / 职责上移）统一处理。</p>
     */
    @Data
    class SubTaskDetail {
        private Long subTaskId;
        private Long taskId;
        private String title;
        /** 执行内容与边界（做什么、边界在哪里）——外部执行者此前完全不可见的字段。 */
        private String content;
        /** 交付物要求（完成后产出什么，必须具体可检查）。 */
        private String deliverable;
        /** 验收标准（审查侧按此核验，执行侧开工前必须逐条自检）。 */
        private String acceptance;
        /** 执行约束（不许改 / 不许越界事项；COARSE 档必填，其余档位可空）。 */
        private String constraints;
        /** 不确定性申报（ASSUMPTION 可自行验证 / 推翻；UNCONFIRMED 须先验证，验证不了走 reportBlocked）。 */
        private List<Uncertainty> uncertainties;
        /** 子任务级 ∪ 任务级技能标签（合并清单，与执行侧注入 / 审查侧核验同源）。 */
        private List<String> requiredSkills;
        /** 优先级：HIGH / MEDIUM / LOW。 */
        private String priority;
        /** SubTaskStatus：PENDING / ASSIGNED / IN_PROGRESS / REVIEW / DONE / ... */
        private String status;
        /** true=契约定义子任务（产出全局注入所有下游子任务执行上下文）。 */
        private Boolean contract;
        /** 前置子任务 ID 列表（同任务内），空数组=无依赖。 */
        private List<Long> dependsOn;
        /** 截止时间（ISO8601）；null=无时限。 */
        private String deadline;
        /** 已发生的返工次数；>0 说明上一轮被审查驳回，开工前应结合审查评语修正。 */
        private Integer reworkCount;
    }

    @Data
    class HeartbeatResult {
        private boolean ok;
        private Long agentId;
        private String serverTime;
        /** 当前是否持有 ACTIVE 值班租约（false = 未打卡或租约已过期）。 */
        private Boolean onDuty;
        /** 当前 ACTIVE 租约 ID；未在岗时为 null。 */
        private Long leaseId;
        /** 当前 ACTIVE 租约过期时间（ISO8601）；未在岗时为 null。 */
        private String leaseExpiresAt;
        /** 当前 ACTIVE 租约剩余 TTL（秒）；未在岗为 0。 */
        private Long remainingTtlSeconds;
    }

    @Data
    class UploadArtifactResult {
        private boolean ok;
        private Long attachmentId;
        private String storageUrl;
    }

    @Data
    class SubmitResultResult {
        private boolean ok;
        private boolean accepted;
        private boolean idempotent;
        private String status;
        private String reason;
        private Long subTaskId;
        private String resultId;
    }

    @Data
    class ReportBlockedResult {
        private boolean ok;
        private boolean blocked;
        private Long subTaskId;
        private String reason;
    }

    @Data
    class CheckInResult {
        private boolean ok;
        private Long agentId;
        private Long leaseId;
        private String sessionId;
        private String workMode;
        private Integer maxConcurrent;
        private String expiresAt;
        /** 上报技能合并后的完整列表（null = 本次未上报技能，P2 §6.115） */
        private List<String> mergedSkills;
    }

    @Data
    class CheckOutResult {
        private boolean ok;
        private Long agentId;
        private int closedCount;
        private String reason;
        /** 签退后最近一条租约的当前状态（CLOSED=刚签退 / EXPIRED=已过期无需签退 / NONE=从未打卡）。 */
        private String currentStatus;
        /** 最近一条租约 ID；从未打卡为 null。 */
        private Long latestLeaseId;
        /** 最近一条租约过期时间（ISO8601）；从未打卡为 null。 */
        private String latestLeaseExpiresAt;
        /** 最近一条租约关闭原因（仅 CLOSED/EXPIRED 时填写）。 */
        private String latestLeaseClosedReason;
    }

    @Data
    class GetAgentStatusResult {
        private Long agentId;
        private String name;
        /** AgentRole：PLANNER / EXECUTOR / REVIEWER */
        private String role;
        /** AgentStatus（管理态）：ACTIVE / DISABLED */
        private String status;
        /** AgentOnlineStatus（DB 持久值，可能滞后）：ONLINE / IDLE / OFFLINE / SLEEPING */
        private String dbOnlineStatus;
        /** AgentOnlineStatus（实时按 last_seen_time/last_active_time 推算） */
        private String computedOnlineStatus;
        private String lastSeenAt;
        private String lastActiveAt;
        /** 仅 OFFLINE 时非空 */
        private String offlineReason;
        /** 仅 OFFLINE 时非空 */
        private String offlineAt;
        private String serverTime;
    }

    @Data
    class GetDepsSummaryResult {
        private Long subTaskId;
        private Long taskId;
        /** 声明的前置数（dependsOnIdList 长度，含缺失项）。 */
        private Integer depCount;
        /** 实际读到内容的前置数。 */
        private Integer loadedCount;
        /** 内容超限被截断的前置数。 */
        private Integer truncatedCount;
        /** true=收集异常降级（deps 为空），不阻断调用。 */
        private Boolean degraded;
        private List<DepItem> deps;

        @Data
        public static class DepItem {
            private Long subTaskId;
            private String title;
            /** SubTaskStatus：DONE / IN_PROGRESS / ... */
            private String status;
            /** 执行记录摘要（Task Running Spec），可能为 null。 */
            private String summary;
            /** 产出内容本体（物化附件优先，回退 context.lastExecution.output），可能为 null。 */
            private String content;
            /** true=内容超过 4000 字符被截断。 */
            private Boolean truncated;
        }
    }
}
