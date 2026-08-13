package com.helloai.core.agent.service;

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

    /** Agent 签退（结束值班租约）。 */
    CheckOutResult checkOut(Long agentId, String reason);

    /** 查询 Agent 实时状态（DB 持久值 + 实时推算）。 */
    GetAgentStatusResult getAgentStatus(Long agentId);

    /** 汇总前置子任务产出内容（物化附件优先，回退执行记录输出）。 */
    GetDepsSummaryResult getDepsSummary(Long agentId, Long subTaskId);

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
            /** A0-4（§6.63）：收件箱通知摘要（sub_task.rejected/approved 携带 review 评分与评语摘要）。 */
            private String summary;
            /** A0-4（§6.63）：未读/已读状态位（false=未读待 ack，true=已 ack；includeRead=true 时才可能为 true）。 */
            private Boolean read;
            /** A0-1（§6.60）：消息对应子任务已转移给其他 Agent（当前执行者非本 Agent）。 */
            private Boolean reassigned;
            /** A0-1（§6.60）：子任务当前实际执行者 Agent ID（配合 reassigned 使用）。 */
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
    }

    @Data
    class HeartbeatResult {
        private boolean ok;
        private Long agentId;
        private String serverTime;
        /** A0-6：当前是否持有 ACTIVE 值班租约（false = 未打卡或租约已过期）。 */
        private Boolean onDuty;
        /** A0-6：当前 ACTIVE 租约 ID；未在岗时为 null。 */
        private Long leaseId;
        /** A0-6：当前 ACTIVE 租约过期时间（ISO8601）；未在岗时为 null。 */
        private String leaseExpiresAt;
        /** A0-6：当前 ACTIVE 租约剩余 TTL（秒）；未在岗为 0。 */
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
    }

    @Data
    class CheckOutResult {
        private boolean ok;
        private Long agentId;
        private int closedCount;
        private String reason;
        /** A0-6：签退后最近一条租约的当前状态（CLOSED=刚签退 / EXPIRED=已过期无需签退 / NONE=从未打卡）。 */
        private String currentStatus;
        /** A0-6：最近一条租约 ID；从未打卡为 null。 */
        private Long latestLeaseId;
        /** A0-6：最近一条租约过期时间（ISO8601）；从未打卡为 null。 */
        private String latestLeaseExpiresAt;
        /** A0-6：最近一条租约关闭原因（仅 CLOSED/EXPIRED 时填写）。 */
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
        /** AgentOnlineStatus（实时按 last_seen_at/last_active_at 推算） */
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
