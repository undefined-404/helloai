package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.service.McpToolService.AckResult;
import com.helloai.core.agent.service.McpToolService.CheckInResult;
import com.helloai.core.agent.service.McpToolService.CheckOutResult;
import com.helloai.core.agent.service.McpToolService.ClaimSubTaskResult;
import com.helloai.core.agent.service.McpToolService.GetAgentStatusResult;
import com.helloai.core.agent.service.McpToolService.GetDepsSummaryResult;
import com.helloai.core.agent.service.McpToolService.HeartbeatResult;
import com.helloai.core.agent.service.McpToolService.PullTasksResult;
import com.helloai.core.agent.service.McpToolService.ReportBlockedResult;
import com.helloai.core.agent.service.McpToolService.SubmitResultResult;
import com.helloai.core.agent.service.McpToolService.UploadArtifactResult;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.command.ExecutionResultReport;
import com.helloai.core.agent.entity.*;
import com.helloai.core.task.entity.*;
import com.helloai.core.system.entity.*;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * MCP 工具核心逻辑。
 * 每个方法对应一个 MCP 工具，供 Controller（REST + JSON-RPC）统一调用。
 *
 * <p><b>§7.8 类规模拆分评审结论（2026-08-23）</b>：本类为 MCP 工具协议层汇聚点，
 * 超 500 行 / 8 依赖红线，按 §7.8 选项二书面声明不继续拆分：</p>
 * <ul>
 *     <li>已剥离：领域状态机与任务流转（SubTaskService）、执行编排与结果回写
 *         （SubTaskExecutionServiceImpl / ExecutionResultHandler）、MCP 服务器管理
 *         （AgentMcpServerService）、值守租约（AgentDutyLeaseService）、
 *         附件登记（AttachmentService）；</li>
 *     <li>剩余职责：工具协议适配（入参校验 / 鉴权守卫 / 幂等 / 结果组装），方法体为
 *         薄转发，业务逻辑已全部下沉领域服务；</li>
 *     <li>不拆理由：所有工具共享同一套 agent 活跃度守卫、工具开关与租约续期前置
 *         （assertAgentActive / assertToolEnabled / refreshDutyLease），拆分后守卫逻辑
 *         被迫复制或跨类回调；按工具分组拆分只会平移行数。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolServiceImpl implements McpToolService {

    private final AgentService agentService;
    private final AgentInboxService agentInboxService;
    private final AgentMcpServerService agentMcpServerService;
    private final SubTaskService subTaskService;
    private final HeartbeatService heartbeatService;
    private final AttachmentService attachmentService;
    private final ExecutionResultHandler executionResultHandler;
    private final AgentDutyLeaseService agentDutyLeaseService;
    private final TaskRunningSpecService taskRunningSpecService;

    /** 依赖产出单条内容截断上限（与 SubTaskExecutionService.DEP_CONTENT_MAX_CHARS 对齐，避免两处口径漂移）。 */
    private static final int DEP_CONTENT_MAX_CHARS = 4000;

    /** 通知/评语摘要截断上限（收件箱 summary、review 摘要等）。 */
    private static final int SUMMARY_MAX_CHARS = 200;

    // ================================================================
    // pullTasks
    // ================================================================

    /**
     * 拉取 Agent 的待处理收件箱消息。
     * 只读，不标记已读。ack 才标记已读。
     */
    public PullTasksResult pullTasks(Long agentId, String role, int max) {
        return pullTasks(agentId, role, max, false);
    }

    /**
     * 拉取 Agent 的收件箱消息。
     * 只读，不标记已读。ack 才标记已读。
     *
     * @param includeRead ：true 时在未读之外附带最近已读消息（未读优先，已读按 read_time 倒序
     *                    补齐配额），每条消息带 read 状态位，供外部 Agent 轮询时区分新消息与已 ack 历史；
     *                    false（默认）保持原语义仅返回未读。
     */
    public PullTasksResult pullTasks(Long agentId, String role, int max, boolean includeRead) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "pullTasks");
        refreshDutyLease(agentId); // 轮询工具顺带续租，轮询期即保活期

        // 应用参数约束（agent_mcp_server.param_constraints.max 优先）
        Map<String, Object> constraints = agentMcpServerService.getParamConstraints(agentId, "pullTasks");
        if (constraints != null && constraints.get("max") instanceof Number constraintMax) {
            max = Math.min(max, constraintMax.intValue());
        }
        max = Math.max(1, Math.min(max, 200));

        List<AgentInbox> inboxes = new ArrayList<>(agentInboxService.getUnread(agentId, max));
        if (includeRead) {
            // 未读优先（保持既有排序），已读按 read_time 倒序补齐剩余配额
            int readQuota = max - inboxes.size();
            if (readQuota > 0) {
                inboxes.addAll(agentInboxService.getRecentRead(agentId, readQuota));
            }
        }

        List<PullTasksResult.Message> messages = new ArrayList<>();
        for (AgentInbox inbox : inboxes) {
            PullTasksResult.Message msg = new PullTasksResult.Message();
            msg.setMessageId("inbox-" + inbox.getId());
            msg.setType(inbox.getEventType());
            msg.setSubTaskId(inbox.getRefId());
            msg.setTitle(inbox.getTitle());
            msg.setPriority(inbox.getPriority());
            // 透传收件箱摘要（sub_task.rejected/approved 消息携带 review 评分与评语摘要）
            msg.setSummary(inbox.getSummary());
            // 未读/已读状态位（false=未读待 ack，true=已 ack）
            msg.setRead(inbox.getIsRead() != null && inbox.getIsRead() == 1);

            // 如果 refType=sub_task，补充 taskId 和 deadline
            if ("sub_task".equals(inbox.getRefType()) && inbox.getRefId() != null) {
                SubTask subTask = subTaskService.getById(inbox.getRefId());
                if (subTask != null) {
                    msg.setTaskId(subTask.getTaskId());
                    msg.setDeadline(subTask.getDeadline() != null ? subTask.getDeadline().toString() : null);
                    // 曾分配给我但已转移的子任务打标记（配合 sub_task.reassigned / unassigned
                    // 撤销通知），让 Agent 明确知道"这条消息对应的任务已不在我名下"，避免误继续干活；
                    // 执行者已清空（回收）同样打标，currentAgentId 保持 null
                    Long currentAgentId = subTask.getAssignedAgentId();
                    if (currentAgentId == null || !currentAgentId.equals(agentId)) {
                        msg.setReassigned(true);
                        if (currentAgentId != null) {
                            msg.setCurrentAgentId(currentAgentId);
                        }
                    }
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
        refreshDutyLease(agentId); // 处理收件箱消息顺带续租

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
     * 原子认领子任务。并发安全：使用 SubTaskService.claimAtomic（DB 条件更新）。
     *
     * @return claimed=true 表示认领成功，claimed=false 表示已被他人抢走或状态已变
     */
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubTaskResult claimSubTask(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "claimSubTask");
        refreshDutyLease(agentId); // 认领/开工顺带续租，长任务执行期保活

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(false);
            result.setClaimed(false);
            result.setReason("subtask_not_found");
            return result;
        }

        // 已归属于自己 → 幂等返回成功
        if (agentId.equals(subTask.getAssignedAgentId())
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
        if (subTask.getAssignedAgentId() != null && !agentId.equals(subTask.getAssignedAgentId())) {
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
        if (!subTaskService.claimAtomic(subTaskId, agentId)) {
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
     * 心跳上报。刷新 Agent 的 last_seen_time，维持在线状态。
     * 幂等，频繁调用无副作用。
     *
     * <p>：响应附带当前值班租约状态（onDuty / leaseId / leaseExpiresAt /
     * remainingTtlSeconds），供 Agent 每次心跳自检续约——租约剩余时间不足时可主动
     * 重新 checkIn，避免被静默切到离岗。</p>
     *
     * <p>：心跳顺带自动续租——有 ACTIVE 租约时按原 TTL 窗口延长
     * expire_time，remainingTtlSeconds 为续租后的剩余 TTL；外部 Agent 只要保持
     * 轮询本工具即可持续在岗，无需手动重做 checkIn。</p>
     */
    public HeartbeatResult heartbeat(Long agentId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "heartbeat");

        heartbeatService.seen(agentId);
        // 心跳顺带续租——返回的 remainingTtlSeconds 为续租后的剩余 TTL，
        // 外部 Agent 只要保持轮询 heartbeat 即可持续在岗，无需手动重做 checkIn。
        refreshDutyLease(agentId);

        OffsetDateTime now = OffsetDateTime.now();
        HeartbeatResult result = new HeartbeatResult();
        result.setOk(true);
        result.setAgentId(agentId);
        result.setServerTime(now.toString());

        AgentDutyLease active = agentDutyLeaseService.getActiveLease(agentId);
        if (active != null) {
            result.setOnDuty(true);
            result.setLeaseId(active.getId());
            result.setLeaseExpiresAt(active.getExpireTime() != null ? active.getExpireTime().toString() : null);
            long remainSeconds = 0L;
            if (active.getExpireTime() != null) {
                remainSeconds = Duration.between(now, active.getExpireTime()).getSeconds();
            }
            result.setRemainingTtlSeconds(remainSeconds > 0 ? remainSeconds : 0L);
        } else {
            result.setOnDuty(false);
            result.setRemainingTtlSeconds(0L);
        }
        return result;
    }

    // ================================================================
    // uploadArtifact
    // ================================================================

    /**
     * 上传产物附件元数据。文件内容场景请先经 POST /api/artifacts/upload 上传
     * （平台转存 MinIO 并注册一步到位）；本工具仅适用于「对象已在别处可访问」时的
     * 登记（只注册 DB 元数据记录，不传输文件内容）。
     * 平台可直读 minio:// 附件（下载与执行证据核验），
     * storageUrl 建议按 {注册名}/{yyyy}/{MM}/{taskId}/{subTaskId}/ 组织。
     */
    @Transactional(rollbackFor = Exception.class)
    public UploadArtifactResult uploadArtifact(Long agentId, Long subTaskId,
                                                String fileName, String mimeType,
                                                Long fileSize, String storageUrl) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "uploadArtifact");
        refreshDutyLease(agentId); // 产物登记顺带续租

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
        refreshDutyLease(agentId); // 结果提交顺带续租

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
        if (subTask.getAssignedAgentId() == null || !agentId.equals(subTask.getAssignedAgentId())) {
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
        refreshDutyLease(agentId); // 阻塞上报顺带续租

        if (reason == null || reason.isBlank()) {
            throw new BizException("reason 不能为空");
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.getAssignedAgentId())) {
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
    // checkIn （AgentHub P0-A：值班打卡）
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
     * <p>E1 动态 TTL：ttlMinutes 为 null 时不再固定 30 分钟，改由
     * {@link AgentDutyLeaseService#resolveTtlMinutes} 按 Agent 表现动态推断
     * （低分短窗口快速回收、高分长窗口减少续约）。</p>
     *
     * @param agentId       Agent ID（鉴权后由服务端强制覆盖）
     * @param workMode      工作模式（如 AUTO），null 时保持数据库默认
     * @param maxConcurrent 最大并发子任务数，null 默认 1
     * @param ttlMinutes    租约有效期（分钟），null 时按 Agent 表现动态推断
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes) {
        return checkIn(agentId, workMode, maxConcurrent, ttlMinutes, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes,
                                 List<String> reportedSkills) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "checkIn");

        int ttl = agentDutyLeaseService.resolveTtlMinutes(agentId, ttlMinutes);
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

        // P2：上报技能与既有 agent.skills 取并集（只增不减），best-effort 不阻断打卡
        List<String> mergedSkills = mergeReportedSkills(agentId, reportedSkills);

        CheckInResult result = new CheckInResult();
        result.setOk(true);
        result.setAgentId(agentId);
        result.setLeaseId(lease.getId());
        result.setSessionId(lease.getSessionId());
        result.setWorkMode(lease.getWorkMode());
        result.setMaxConcurrent(lease.getMaxConcurrent());
        result.setExpiresAt(lease.getExpireTime() != null ? lease.getExpireTime().toString() : null);
        result.setMergedSkills(mergedSkills);
        return result;
    }

    /**
     * 把 checkIn 上报的技能并入 {@code agent.skills}（P2 §6.115，值班能力分级）。
     *
     * <p>语义：归一化（{@link SkillNormalizer#normalizeAll}）后与既有列表取并集，
     * 只增不减——某次打卡漏报不会清掉历史已声明技能；无新增时不写库。
     * 失败仅告警并返回 null（打卡主链不受影响）。</p>
     *
     * @return 合并后的完整技能列表；本次未上报或合并失败返回 null
     */
    private List<String> mergeReportedSkills(Long agentId, List<String> reportedSkills) {
        if (reportedSkills == null || reportedSkills.isEmpty()) {
            return null;
        }
        try {
            Agent agent = agentService.getById(agentId);
            if (agent == null) {
                return null;
            }
            List<String> existing = SkillNormalizer.normalizeAll(agent.getSkills());
            LinkedHashSet<String> merged = new LinkedHashSet<>(existing);
            merged.addAll(SkillNormalizer.normalizeAll(reportedSkills));
            List<String> result = new ArrayList<>(merged);
            if (!result.equals(existing)) {
                agent.setSkills(result);
                agentService.updateById(agent);
                log.info("checkIn 技能上报合并: agentId={}, added={}, merged={}",
                        agentId, result.size() - existing.size(), result);
            }
            return result;
        } catch (Exception e) {
            log.warn("checkIn 技能上报合并失败（不阻断打卡）: agentId={}, err={}", agentId, e.getMessage());
            return null;
        }
    }

    // ================================================================
    // checkOut （AgentHub P0-A：值班签退）
    // ================================================================

    /**
     * Agent 打卡下班（关闭当前 ACTIVE 值班租约）。
     *
     * <p>本轮仅落地租约状态回写为 CLOSED；离岗补偿（对已 ASSIGNED 但未 IN_PROGRESS 的子任务
     * 触发重分配）由既有 {@code SubTaskDispatchService.redispatchAssignedTimeout} 通过
     * 常规超时兜底路径完成，checkOut 工具不直接触发。</p>
     *
     * <p>幂等：Agent 当前无 ACTIVE 租约时返回 ok=true, closedCount=0，并附带
     * 最近一条租约的当前状态（currentStatus=EXPIRED 表示租约已过期无需签退 / NONE 表示
     * 从未打卡），供 Agent 自检。</p>
     *
     * @param agentId Agent ID（鉴权后由服务端强制覆盖）
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

        // 幂等返回当前状态——最近一条租约的状态（ACTIVE 已关为 CLOSED / 已过期 EXPIRED / 从未打卡 NONE）
        AgentDutyLease latest = agentDutyLeaseService.getLatestLease(agentId);
        if (latest != null) {
            result.setCurrentStatus(latest.getStatus() != null ? latest.getStatus().name() : null);
            result.setLatestLeaseId(latest.getId());
            result.setLatestLeaseExpiresAt(latest.getExpireTime() != null ? latest.getExpireTime().toString() : null);
            result.setLatestLeaseClosedReason(latest.getCloseReason());
        } else {
            result.setCurrentStatus("NONE");
        }
        return result;
    }

    // ================================================================
    // getAgentStatus（业务下沉至 McpToolService，REST 别名通道可复用）
    // ================================================================

    /**
     * 查询 Agent 自身状态（管理态 + DB 持久在线态 + 实时计算态）。
     *
     * <p>业务逻辑统一下沉到 {@link McpToolService}，MCP SSE 与 REST 别名
     * 两条通道工具矩阵完全对齐（10 工具），避免实现漂移。</p>
     */
    public GetAgentStatusResult getAgentStatus(Long agentId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "getAgentStatus");
        refreshDutyLease(agentId); // 状态自检顺带续租

        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        AgentOnlineStatus computed = heartbeatService.checkOnlineStatus(agent);

        GetAgentStatusResult r = new GetAgentStatusResult();
        r.setAgentId(agentId);
        r.setName(agent.getName());
        r.setRole(agent.getRole() != null ? agent.getRole().name() : null);
        r.setStatus(agent.getStatus() != null ? agent.getStatus().name() : null);
        r.setDbOnlineStatus(agent.getOnlineStatus() != null ? agent.getOnlineStatus().name() : null);
        r.setComputedOnlineStatus(computed != null ? computed.name() : null);
        r.setLastSeenAt(agent.getLastSeenTime() != null ? agent.getLastSeenTime().toString() : null);
        r.setLastActiveAt(agent.getLastActiveTime() != null ? agent.getLastActiveTime().toString() : null);
        r.setOfflineReason(agent.getOfflineReason());
        r.setOfflineAt(agent.getOfflineTime() != null ? agent.getOfflineTime().toString() : null);
        r.setServerTime(java.time.OffsetDateTime.now().toString());
        return r;
    }

    // ================================================================
    // getDepsSummary（外部 Agent 主动获取前置产出摘要）
    // ================================================================

    /**
     * 获取指定子任务的直接前置产出摘要（结构化）。
     *
     * <p>执行链在 executeOnce 时已把依赖产出注入 Prompt（buildDependencySection），
     * 但外部 Agent 开工前无法主动查看——本工具提供接口层能力，数据口径与执行链同源：
     * 执行记录摘要（TaskRunningSpec）优先 + 内容本体（物化附件 local:// 平台直读，回退
     * {@code context.lastExecution.output}），单条超 {@link #DEP_CONTENT_MAX_CHARS} 字符截断并打标；
     * 任何异常降级为 degraded（deps 为空，不阻断调用），与执行链降级哲学一致。</p>
     */
    public GetDepsSummaryResult getDepsSummary(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "getDepsSummary");
        refreshDutyLease(agentId); // 前置产出拉取顺带续租

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        List<Long> dependsOn = subTask.dependsOnIdList();
        GetDepsSummaryResult result = new GetDepsSummaryResult();
        result.setSubTaskId(subTaskId);
        result.setTaskId(subTask.getTaskId());
        result.setDegraded(false);
        if (dependsOn == null || dependsOn.isEmpty()) {
            result.setDepCount(0);
            result.setLoadedCount(0);
            result.setTruncatedCount(0);
            result.setDeps(Collections.emptyList());
            return result;
        }

        try {
            List<SubTask> deps = subTaskService.listByIds(dependsOn);
            Map<Long, SubTask> depMap = new HashMap<>();
            if (deps != null) {
                for (SubTask dep : deps) {
                    depMap.put(dep.getId(), dep);
                }
            }

            List<GetDepsSummaryResult.DepItem> items = new ArrayList<>();
            int loadedCount = 0;
            int truncatedCount = 0;
            for (Long depId : dependsOn) {
                SubTask dep = depMap.get(depId);
                if (dep == null) {
                    continue;
                }
                GetDepsSummaryResult.DepItem item = new GetDepsSummaryResult.DepItem();
                item.setSubTaskId(dep.getId());
                item.setTitle(dep.getTitle());
                item.setStatus(dep.getStatus() != null ? dep.getStatus().name() : null);
                ExecutionRecord record = taskRunningSpecService.findRecord(subTask.getTaskId(), depId);
                if (record != null && record.summary() != null && !record.summary().isBlank()) {
                    item.setSummary(record.summary());
                }
                String content = loadUpstreamContent(dep);
                if (content != null && !content.isBlank()) {
                    if (content.length() > DEP_CONTENT_MAX_CHARS) {
                        content = content.substring(0, DEP_CONTENT_MAX_CHARS);
                        item.setTruncated(true);
                        truncatedCount++;
                    }
                    item.setContent(content);
                    loadedCount++;
                }
                items.add(item);
            }
            result.setDeps(items);
            result.setDepCount(dependsOn.size());
            result.setLoadedCount(loadedCount);
            result.setTruncatedCount(truncatedCount);
            return result;
        } catch (Exception e) {
            log.warn("getDepsSummary 收集失败，降级返回: subTaskId={}, err={}", subTaskId, e.getMessage());
            result.setDeps(Collections.emptyList());
            result.setDepCount(dependsOn.size());
            result.setLoadedCount(0);
            result.setTruncatedCount(0);
            result.setDegraded(true);
            return result;
        }
    }

    /**
     * 读取前置子任务的完成内容本体：物化附件（local:// 平台直读，仅 ACTIVE 有效版本——
     * 同名历史版本已由 {@code AttachmentService.register} 自动去活）优先，失败/无附件回退
     * {@code context.lastExecution.output} 原始产出；两者均无返回 null。
     * 与 SubTaskExecutionService.loadUpstreamContent 同源实现（消费方隔离，避免渲染逻辑耦合）。
     */
    private String loadUpstreamContent(SubTask dep) {
        try {
            List<Attachment> attachments = attachmentService.listActive(dep.getId());
            if (attachments != null) {
                for (Attachment attachment : attachments) {
                    if (attachmentService.isContentLoadable(attachment)) {
                        byte[] bytes = attachmentService.loadContent(attachment.getId());
                        if (bytes != null && bytes.length > 0) {
                            return new String(bytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取前置物化附件内容失败，回退原始产出: subTaskId={}, err={}", dep.getId(), e.getMessage());
        }
        return SubTaskOutputExtractor.extractExecutionOutput(dep);
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

    /**
     * 工具调用自动续租（ §6.67 + E1 动态 TTL 自适应）：有 ACTIVE 租约时顺带延长
     * {@code expire_time}，长任务执行期间任何工具调用即可保活，无需 Agent 主动
     * 重做 checkIn；无 ACTIVE 租约时跳过（不自动打卡，保持 checkIn 的打卡语义）。
     *
     * <p>续约窗口由 {@code AgentDutyLeaseService.adaptiveRenew} 按当前状态动态计算：
     * 有在跑子任务用最大窗口（任务执行期稳定保活），空闲按表现分动态窗口
     * （低分短、高分长）；续约失败仅告警不阻断工具调用（顺带动作）。
     * checkIn/checkOut 不接入本方法：前者签发新租约，后者结束租约。</p>
     */
    private void refreshDutyLease(Long agentId) {
        if (agentId == null) {
            return;
        }
        try {
            agentDutyLeaseService.adaptiveRenew(agentId);
        } catch (Exception e) {
            log.warn("工具调用自动续租失败（不影响主操作）: agentId={}, err={}", agentId, e.getMessage());
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

}
