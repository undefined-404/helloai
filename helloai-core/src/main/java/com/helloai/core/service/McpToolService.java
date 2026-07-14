package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.*;
import com.helloai.core.mapper.SubTaskMapper;
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

        subTaskService.block(subTaskId);

        ReportBlockedResult result = new ReportBlockedResult();
        result.setOk(true);
        result.setBlocked(true);
        result.setSubTaskId(subTaskId);
        result.setReason(reason);
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
    public static class ReportBlockedResult {
        private boolean ok;
        private boolean blocked;
        private Long subTaskId;
        private String reason;
    }
}
