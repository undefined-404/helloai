package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.command.ExecutionResultReport;
import com.helloai.core.agent.entity.*;
import com.helloai.core.task.entity.*;
import com.helloai.core.system.entity.*;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.system.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import com.helloai.core.observability.HeartbeatService;

/**
 * MCP 工具核心逻辑。
 * 每个方法对应一个 MCP 工具，供 Controller（REST + JSON-RPC）统一调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private final AgentService agentService;
    private final AgentInboxService agentInboxService;
    private final AgentMcpServerService agentMcpServerService;
    private final SubTaskService subTaskService;
    private final SubTaskMapper subTaskMapper;
    private final HeartbeatService heartbeatService;
    private final AttachmentService attachmentService;
    private final ExecutionResultHandler executionResultHandler;
    private final AgentDutyLeaseService agentDutyLeaseService;

    // ================================================================
    // pullTasks
    // ================================================================

    /**
     * 拉取 Agent 的待处理收件箱消息。
     * 只读，不标记已读。ack 才标记已读。
     */
    public PullTasksResult pullTasks(Long agentId, String role, int max) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "pullTasks");

        // 应用参数约束（agent_mcp_server.param_constraints.max 优先）
        Map<String, Object> constraints = agentMcpServerService.getParamConstraints(agentId, "pullTasks");
        if (constraints != null && constraints.get("max") instanceof Number constraintMax) {
            max = Math.min(max, constraintMax.intValue());
        }
        max = Math.max(1, Math.min(max, 200));

        List<AgentInbox> inboxes = agentInboxService.getUnread(agentId, max);

        List<PullTasksResult.Message> messages = new ArrayList<>();
        for (AgentInbox inbox : inboxes) {
            PullTasksResult.Message msg = new PullTasksResult.Message();
            msg.setMessageId("inbox-" + inbox.getId());
            msg.setType(inbox.getEventType());
            msg.setSubTaskId(inbox.getRefId());
            msg.setTitle(inbox.getTitle());
            msg.setPriority(inbox.getPriority());

            // 如果 refType=sub_task，补充 taskId 和 deadline
            if ("sub_task".equals(inbox.getRefType()) && inbox.getRefId() != null) {
                SubTask subTask = subTaskService.getById(inbox.getRefId());
                if (subTask != null) {
                    msg.setTaskId(subTask.getTaskId());
                    msg.setDeadline(subTask.getDeadline() != null ? subTask.getDeadline().toString() : null);
                }
            }

            messages.add(msg);
        }

        PullTasksResult result = new PullTasksResult();
        result.setMessages(messages);
        return result;
    }

    // ================================================================
    // ack
    // ================================================================

    /**
     * 确认收件箱消息已处理。幂等：重复 ack 返回成功。
     *
     * @param agentId   Agent ID
     * @param messageId 格式 "inbox-{id}"
     */
    @Transactional(rollbackFor = Exception.class)
    public AckResult ack(Long agentId, String messageId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "ack");

        Long inboxId = parseInboxId(messageId);
        try {
            agentInboxService.markRead(agentId, inboxId);
        } catch (BizException e) {
            // 消息不存在或不属于该 Agent → 仍然幂等返回成功
            log.debug("ack 幂等: agentId={}, messageId={}, reason={}", agentId, messageId, e.getMessage());
        }

        AckResult result = new AckResult();
        result.setOk(true);
        result.setAcknowledged(true);
        result.setMessageId(messageId);
        return result;
    }

    // ================================================================
    // claimSubTask
    // ================================================================

    /**
     * 原子认领子任务。并发安全：使用 SubTaskMapper.claimAtomic（DB 条件更新）。
     *
     * @return claimed=true 表示认领成功，claimed=false 表示已被他人抢走或状态已变
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubTaskResult claimSubTask(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "claimSubTask");

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(false);
            result.setClaimed(false);
            result.setReason("subtask_not_found");
            return result;
        }

        // 已归属于自己 → 幂等返回成功
        if (agentId.equals(subTask.getAssignedAgent())
                && (subTask.getStatus() == SubTaskStatus.ASSIGNED
                    || subTask.getStatus() == SubTaskStatus.IN_PROGRESS)) {
            heartbeatService.active(agentId);
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(true);
            result.setAssignedAgent(agentId);
            result.setSubTaskId(subTaskId);
            result.setVersion(subTask.getVersion());
            return result;
        }

        // 已归属于他人
        if (subTask.getAssignedAgent() != null && !agentId.equals(subTask.getAssignedAgent())) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("already_claimed_by_other");
            return result;
        }

        // 非 PENDING 状态
        if (subTask.getStatus() != SubTaskStatus.PENDING) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("invalid_status:" + subTask.getStatus());
            return result;
        }

        // 原子条件更新: WHERE status='PENDING' AND (assigned_agent IS NULL OR = agentId)
        int affected = subTaskMapper.claimAtomic(subTaskId, agentId);
        if (affected == 0) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("race_condition_or_invalid_status");
            return result;
        }

        heartbeatService.active(agentId);

        // 重新读取获取最新 version
        SubTask updated = subTaskService.getById(subTaskId);

        ClaimSubTaskResult result = new ClaimSubTaskResult();
        result.setOk(true);
        result.setClaimed(true);
        result.setAssignedAgent(agentId);
        result.setSubTaskId(subTaskId);
        result.setVersion(updated != null ? updated.getVersion() : subTask.getVersion() + 1);
        return result;
    }

    // ================================================================
    // heartbeat
    // ================================================================

    /**
     * 心跳上报。刷新 Agent 的 last_seen_at，维持在线状态。
     * 幂等，频繁调用无副作用。
     */
    public HeartbeatResult heartbeat(Long agentId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "heartbeat");

        heartbeatService.seen(agentId);

        HeartbeatResult result = new HeartbeatResult();
        result.setOk(true);
        result.setAgentId(agentId);
        result.setServerTime(java.time.OffsetDateTime.now().toString());
        return result;
    }

    // ================================================================
    // uploadArtifact
    // ================================================================

    /**
     * 上传产物附件元数据。实际文件内容由客户端直接上传到 MinIO/对象存储，
     * 本工具只注册 DB 元数据记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadArtifactResult uploadArtifact(Long agentId, Long subTaskId,
                                                String fileName, String mimeType,
                                                Long fileSize, String storageUrl) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "uploadArtifact");

        if (fileName == null || fileName.isBlank()) {
            throw new BizException("fileName 不能为空");
        }
        if (storageUrl == null || storageUrl.isBlank()) {
            throw new BizException("storageUrl 不能为空");
        }

        Attachment attachment = attachmentService.register(agentId, subTaskId,
                fileName, mimeType, fileSize, storageUrl);

        UploadArtifactResult result = new UploadArtifactResult();
        result.setOk(true);
        result.setAttachmentId(attachment.getId());
        result.setStorageUrl(storageUrl);
        return result;
    }

    // ================================================================
    // submitResult
    // ================================================================

    @Transactional(rollbackFor = Exception.class)
    public SubmitResultResult submitResult(Long agentId, Long subTaskId, String resultId,
                                          Boolean success, String output, String error, String finishReason) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "submitResult");

        if (subTaskId == null) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("subTaskId_required");
            return r;
        }
        if (success == null) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("success_required");
            return r;
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("subtask_not_found");
            return r;
        }
        if (subTask.getAssignedAgent() == null || !agentId.equals(subTask.getAssignedAgent())) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("not_task_owner");
            return r;
        }

        if (subTask.getStatus() == SubTaskStatus.ASSIGNED) {
            subTaskService.start(subTaskId);
            subTask = subTaskService.getById(subTaskId);
        }
        if (subTask == null || subTask.getStatus() != SubTaskStatus.IN_PROGRESS) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("invalid_status:" + (subTask != null ? subTask.getStatus() : "null"));
            return r;
        }

        ExecutionResultReport report = new ExecutionResultReport();
        report.setSubTaskId(subTaskId);
        report.setAgentId(agentId);
        report.setSource("EXTERNAL");
        report.setIdempotencyKey(resultId);
        report.setSuccess(success);
        report.setExecutorName("cli_client");
        report.setFinishReason(finishReason);
        report.setTokenUsage(null);
        report.setOutput(output);
        report.setError(error);

        ExecutionResultHandler.ExecutionResultApplyResult applyResult = executionResultHandler.handleReport(report);

        heartbeatService.active(agentId);

        SubmitResultResult r = new SubmitResultResult();
        r.setOk(true);
        r.setAccepted(applyResult != null && applyResult.isApplied());
        r.setIdempotent(applyResult != null && applyResult.isIdempotent());
        r.setStatus(applyResult != null ? applyResult.getStatus() : "unknown");
        r.setSubTaskId(subTaskId);
        r.setResultId(resultId);
        return r;
    }

    // ================================================================
    // reportBlocked
    // ================================================================

    /**
     * 上报任务阻塞。EXECUTOR 遇到无法自行解决的阻塞时调用。
     * 内部调用 SubTaskService.block()，自动通知所有 PLANNER 排障。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportBlockedResult reportBlocked(Long agentId, Long subTaskId, String reason) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "reportBlocked");

        if (reason == null || reason.isBlank()) {
            throw new BizException("reason 不能为空");
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.getAssignedAgent())) {
            throw new BizException("只能阻塞自己名下的子任务");
        }

        subTaskService.block(subTaskId, reason, agentId);

        ReportBlockedResult result = new ReportBlockedResult();
        result.setOk(true);
        result.setBlocked(true);
        result.setSubTaskId(subTaskId);
        result.setReason(reason);
        return result;
    }

    // ================================================================
    // checkIn （AgentHub V1 P0-A：值班打卡）
    // ================================================================

    /**
     * Agent 打卡上班（开启值班租约）。
     *
     * <p>底层复用 {@link AgentDutyLeaseService#startLease}：
     * 事务内先关闭该 Agent 的所有旧 ACTIVE 租约，再新建一条。</p>
     *
     * <p>幂等语义：重复 checkIn 不会失败，旧 ACTIVE 租约会被关闭为 CLOSED（reason=new_lease_start），
     * 新的 sessionId 会覆盖返回。</p>
     *
     * @param agentId       Agent ID（M4 鉴权后由服务端强制覆盖）
     * @param workMode      工作模式（如 AUTO），null 时保持数据库默认
     * @param maxConcurrent 最大并发子任务数，null 默认 1
     * @param ttlMinutes    租约有效期（分钟），null 默认 30
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "checkIn");

        int ttl = (ttlMinutes == null || ttlMinutes <= 0) ? 30 : ttlMinutes;
        // N12 P1 STRICT 独占报锁：严格校验入参，非法值立即拒绝（不被默默降级为 AUTO）。
        WorkMode mode;
        try {
            mode = WorkMode.strictParse(workMode);
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage());
        }
        AgentDutyLease lease = agentDutyLeaseService.startLease(agentId, mode.name(), maxConcurrent, ttl);

        // 顺带刷心跳，避免 checkIn 后仍被判定 OFFLINE
        heartbeatService.seen(agentId);

        CheckInResult result = new CheckInResult();
        result.setOk(true);
        result.setAgentId(agentId);
        result.setLeaseId(lease.getId());
        result.setSessionId(lease.getSessionId());
        result.setWorkMode(lease.getWorkMode());
        result.setMaxConcurrent(lease.getMaxConcurrent());
        result.setExpiresAt(lease.getExpiresAt() != null ? lease.getExpiresAt().toString() : null);
        return result;
    }

    // ================================================================
    // checkOut （AgentHub V1 P0-A：值班签退）
    // ================================================================

    /**
     * Agent 打卡下班（关闭当前 ACTIVE 值班租约）。
     *
     * <p>本轮仅落地租约状态回写为 CLOSED；离岗补偿（对已 ASSIGNED 但未 IN_PROGRESS 的子任务
     * 触发重分配）由既有 {@code SubTaskDispatchService.redispatchAssignedTimeout} 通过
     * 常规超时兜底路径完成，checkOut 工具不直接触发。</p>
     *
     * <p>幂等：Agent 当前无 ACTIVE 租约时返回 ok=true, closedCount=0。</p>
     *
     * @param agentId Agent ID（M4 鉴权后由服务端强制覆盖）
     * @param reason  关闭原因，可为 null（默认 manual_close）
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckOutResult checkOut(Long agentId, String reason) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "checkOut");

        String closeReason = (reason == null || reason.isBlank()) ? "manual_close" : reason;
        int closed = agentDutyLeaseService.closeLease(agentId, closeReason);

        CheckOutResult result = new CheckOutResult();
        result.setOk(true);
        result.setAgentId(agentId);
        result.setClosedCount(closed);
        result.setReason(closeReason);
        return result;
    }

    // ================================================================
    // helpers
    // ================================================================

    private void assertAgentActive(Long agentId) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new BizException("Agent 未激活: " + agentId + ", status=" + agent.getStatus());
        }
    }

    private void assertToolEnabled(Long agentId, String toolName) {
        if (!agentMcpServerService.isToolEnabled(agentId, toolName)) {
            throw new BizException("工具未启用: agentId=" + agentId + ", tool=" + toolName);
        }
    }

    private Long parseInboxId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new BizException("messageId 不能为空");
        }
        String stripped = messageId.startsWith("inbox-") ? messageId.substring(6) : messageId;
        try {
            return Long.valueOf(stripped);
        } catch (NumberFormatException e) {
            throw new BizException("无效的 messageId 格式: " + messageId);
        }
    }

    // ================================================================
    // result DTOs (package-private, used by Controller to serialize)
    // ================================================================

    @lombok.Data
    public static class PullTasksResult {
        private List<Message> messages;

        @lombok.Data
        public static class Message {
            private String messageId;
            private String type;
            private Long subTaskId;
            private Long taskId;
            private String title;
            private String priority;
            private String deadline;
        }
    }

    @lombok.Data
    public static class AckResult {
        private boolean ok;
        private boolean acknowledged;
        private String messageId;
    }

    @lombok.Data
    public static class ClaimSubTaskResult {
        private boolean ok;
        private boolean claimed;
        private String reason;
        private Long assignedAgent;
        private Long subTaskId;
        private Integer version;
    }

    @lombok.Data
    public static class HeartbeatResult {
        private boolean ok;
        private Long agentId;
        private String serverTime;
    }

    @lombok.Data
    public static class UploadArtifactResult {
        private boolean ok;
        private Long attachmentId;
        private String storageUrl;
    }

    @lombok.Data
    public static class SubmitResultResult {
        private boolean ok;
        private boolean accepted;
        private boolean idempotent;
        private String status;
        private String reason;
        private Long subTaskId;
        private String resultId;
    }

    @lombok.Data
    public static class ReportBlockedResult {
        private boolean ok;
        private boolean blocked;
        private Long subTaskId;
        private String reason;
    }

    @lombok.Data
    public static class CheckInResult {
        private boolean ok;
        private Long agentId;
        private Long leaseId;
        private String sessionId;
        private String workMode;
        private Integer maxConcurrent;
        private String expiresAt;
    }

    @lombok.Data
    public static class CheckOutResult {
        private boolean ok;
        private Long agentId;
        private int closedCount;
        private String reason;
    }
}
