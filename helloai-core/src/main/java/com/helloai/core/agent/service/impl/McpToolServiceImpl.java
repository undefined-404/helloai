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
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.command.ExecutionResultReport;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.entity.*;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.AttachmentRef;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskClaimConstraint;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.shared.util.TextTruncator;
import com.helloai.core.shared.util.UpstreamAttachmentRenderer;
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
 *     <li>已剥离：领域状态机与任务流转（{@code SubTaskQueryPort} / {@code SubTaskCommandPort}，
 *         2026-10-01 W7 端口化）、执行编排与结果回写
 *         （SubTaskExecutionServiceImpl / ExecutionResultHandler）、MCP 服务器管理
 *         （AgentMcpServerService）、值守租约（AgentDutyLeaseService）、
 *         附件登记与读取（{@code AttachmentPort}，W6/W7）；</li>
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
    private final SubTaskQueryPort subTaskQueryPort;
    private final SubTaskCommandPort subTaskCommandPort;
    private final HeartbeatService heartbeatService;
    private final AttachmentPort attachmentPort;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final ExecutionResultHandler executionResultHandler;
    /** G-006 C2：外部轨迹可观测——认领成功埋点 AGENT_STARTED（事件 write-only，失败仅告警不阻断）。 */
    private final AgentEventRecorder agentEventRecorder;
    private final AgentDutyLeaseService agentDutyLeaseService;
    private final TaskRunningSpecPort taskRunningSpecPort;
    /** P1 认领准入闸门可观测：认领被拒落 task_timeline（{@code sub_task_claim_rejected}，write-only 降级）。 */
    private final TaskTimelinePort taskTimelinePort;

    /**
     * 依赖产出单条内容截断上限（与 SubTaskExecutionService.DEP_CONTENT_MAX_CHARS 对齐，避免两处口径漂移）。
     *
     * <p>2026-10-03 上调：4000 → 64000。所有 LLM 走官方 api-key（DeepSeek 64K~1M 上下文），
     * 4000 对 64K 上下文是自我阉割——外部 agent 通过 getDepsSummary 读不到前置关键产出、
     * 诱发幻觉，属纯沉默成本。与执行链侧 {@code AgentRuntimeContextAssembler.DEP_CONTENT_MAX_CHARS}
     * 及核验/报告侧口径对齐，统一为「正常产出全量注入 + 极端超长兜底截断」。</p>
     */
    private static final int DEP_CONTENT_MAX_CHARS = 64000;

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
                SubTaskSnapshot subTask = subTaskQueryPort.findById(inbox.getRefId());
                if (subTask != null) {
                    msg.setTaskId(subTask.taskId());
                    msg.setDeadline(subTask.deadline() != null ? subTask.deadline().toString() : null);
                    // 曾分配给我但已转移的子任务打标记（配合 sub_task.reassigned / unassigned
                    // 撤销通知），让 Agent 明确知道"这条消息对应的任务已不在我名下"，避免误继续干活；
                    // 执行者已清空（回收）同样打标，currentAgentId 保持 null
                    Long currentAgentId = subTask.assignedAgentId();
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

        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(false);
            result.setClaimed(false);
            result.setReason("subtask_not_found");
            return result;
        }

        // 已归属于自己 → 幂等返回成功
        if (agentId.equals(subTask.assignedAgentId())
                && (subTask.status() == SubTaskStatus.ASSIGNED
                    || subTask.status() == SubTaskStatus.IN_PROGRESS)) {
            heartbeatService.active(agentId);
            // ★2026-10-03 修复「claim 成功却被超时回收」：自动派单已把任务推到 ASSIGNED，
            // 此处幂等分支原样返回会让任务停留 ASSIGNED、update_time 不刷新，
            // 被 AssignedSubTaskTimeoutTask 误判为「超时未领取」收回改派（真机复现：
            // trae claim 成功 version=2，10 分钟后仍被 unclaimed_timeout_reassign 收回）。
            // 认领成功即等价于「开始干活」——顺带推进 IN_PROGRESS（状态机合法转换，
            // startSubTask 同款路径），刷新 update_time，让超时巡检不再扫到它。
            if (subTask.status() == SubTaskStatus.ASSIGNED) {
                subTaskCommandPort.start(subTaskId);
                SubTaskSnapshot started = subTaskQueryPort.findById(subTaskId);
                ClaimSubTaskResult result = new ClaimSubTaskResult();
                result.setOk(true);
                result.setClaimed(true);
                result.setAssignedAgent(agentId);
                result.setSubTaskId(subTaskId);
                result.setVersion(started != null ? started.version() : subTask.version() + 1);
                result.setDetail(buildSubTaskDetail(started != null ? started : subTask));
                log.info("MCP 认领即开工（ASSIGNED→IN_PROGRESS）: subTaskId={}, agentId={}", subTaskId, agentId);
                return result;
            }
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(true);
            result.setAssignedAgent(agentId);
            result.setSubTaskId(subTaskId);
            result.setVersion(subTask.version());
            // 幂等路径同样下发详情：重连 / 重复认领后执行者仍需拿到验收标准与执行边界
            result.setDetail(buildSubTaskDetail(subTask));
            return result;
        }

        // 已归属于他人
        if (subTask.assignedAgentId() != null && !agentId.equals(subTask.assignedAgentId())) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("already_claimed_by_other");
            return result;
        }

        // 非 PENDING 状态
        if (subTask.status() != SubTaskStatus.PENDING) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("invalid_status:" + subTask.status());
            return result;
        }

        // P1 权限闸门：执行者白名单 + 必需技能。此前该口径只在自动派发链
        // （SubTaskDispatchServiceImpl.resolveConstraints → AgentSelectionConstraints.allows）
        // 生效，MCP 认领链完全未校验 ⇒ 白名单外 ACTIVE EXECUTOR 可抢走白名单任务的 PENDING 子任务。
        // 口径整体不透明：白名单/技能单源在提供方 task 域（SubTaskQueryPort.claimConstraint），
        // 本层不复制规则，只用既有 AgentSelectionConstraints.denialReason 判定。
        // null 契约 = 不约束 = 放行（与派发链 DispatchConstraints.of 同语义）。
        // ★ 位置关键：必须在「已归属自己」幂等分支之后 —— 幂等 ASSIGNED/IN_PROGRESS
        //   不得被网关拦住（verify-mcp-e2e.ps1 STEP J 依赖此幂等路径）。
        SubTaskClaimConstraint claimConstraint = subTaskQueryPort.claimConstraint(subTaskId);
        if (claimConstraint != null) {
            // assertAgentActive 已确保 agent 存在；此处为 agent 域原生实体，无需新增跨域依赖
            Agent claimer = agentService.getById(agentId);
            String denial = AgentSelector.AgentSelectionConstraints
                    .of(claimConstraint.allowedAgentIds(), claimConstraint.requiredSkills())
                    .denialReason(claimer);
            if (denial != null) {
                recordClaimRejected(subTask.taskId(), subTaskId, agentId, denial);
                ClaimSubTaskResult result = new ClaimSubTaskResult();
                result.setOk(true);
                result.setClaimed(false);
                result.setReason(denial);
                log.warn("MCP 认领被拒（准入约束未过）: subTaskId={}, agentId={}, reason={}",
                        subTaskId, agentId, denial);
                return result;
            }
        }

        // P0-1 依赖门禁：前置未全部 DONE 时不得认领。与内部分发链共用
        // SubTaskService.isReady 口径（SubTaskDispatchServiceImpl/PendingOrphanTask 已复用），
        // 避免外部 Agent 通道旁路依赖校验、在无前置产出时抢单。
        // 就绪判定整体不透明：口径留提供方（SubTaskQueryPort.isReady），本层不复制规则。
        if (!subTaskQueryPort.isReady(subTaskId)) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("dependency_not_ready");
            return result;
        }

        // 原子条件更新: WHERE status='PENDING' AND (assigned_agent IS NULL OR = agentId)
        if (!subTaskCommandPort.claimAtomic(subTaskId, agentId)) {
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(false);
            result.setReason("race_condition_or_invalid_status");
            return result;
        }

        heartbeatService.active(agentId);

        // 重新读取获取最新 version
        SubTaskSnapshot updated = subTaskQueryPort.findById(subTaskId);

        // G-006 C2：认领成功 = 外部执行 Turn 起点（AGENT_STARTED；write-only，失败不阻断）
        recordClaimStarted(updated != null ? updated.taskId() : subTask.taskId(), subTaskId, updated, agentId);

        ClaimSubTaskResult result = new ClaimSubTaskResult();
        result.setOk(true);
        result.setClaimed(true);
        result.setAssignedAgent(agentId);
        result.setSubTaskId(subTaskId);
        result.setVersion(updated != null ? updated.version() : subTask.version() + 1);
        // 认领即下发子任务全文（含 acceptance / content / constraints / uncertainties）：
        // 免除「认领后再补一次详情调用」的往返，也让未升级到新工具的外部 Agent 直接拿到验收标准
        result.setDetail(buildSubTaskDetail(updated != null ? updated : subTask));
        return result;
    }

    // ================================================================
    // startSubTask
    // ================================================================

    /**
     * 子任务开工：ASSIGNED / REWORK / PAUSED → IN_PROGRESS（P0-2 返工死锁修复）。
     *
     * <p>归属校验以服务端鉴权 agentId 为准（不信任入参）；已是 IN_PROGRESS 时幂等返回成功，
     * 使 Agent 重试调用安全。状态推进复用 {@link SubTaskService#start}，其内部经
     * {@code SubTaskStateMachine} 校验合法性，并由 SubTask 的 {@code @Version} 乐观锁兜住并发。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public StartSubTaskResult startSubTask(Long agentId, Long subTaskId) {
        // 与其余 12 个工具守卫口径一致（Agent 须 ACTIVE + 工具须启用）；不加「逃生口」，
        // 避免返工死锁时唯一的出口也被守卫挡住。isToolEnabled 对 DEFAULT_EXECUTOR_TOOLS
        // 会自动补默认行，存量 Agent 首次调用即启用（幂等）。
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "startSubTask");
        refreshDutyLease(agentId); // 开工顺带续租，与认领/提交同口径
        StartSubTaskResult result = new StartSubTaskResult();
        result.setSubTaskId(subTaskId);

        SubTaskSnapshot subTask = subTaskId != null ? subTaskQueryPort.findById(subTaskId) : null;
        if (subTask == null) {
            result.setAssignedAgent(agentId);
            result.setOk(false);
            result.setStarted(false);
            result.setReason("subtask_not_found");
            return result;
        }
        // 归属校验：仅当前执行者可开工，防止越权推进他人任务
        if (subTask.assignedAgentId() == null || !agentId.equals(subTask.assignedAgentId())) {
            // G-015 B3.2：回显必须反映**真实归属**（可能为 null / 他人），
            // 不能沿用调用方入参 agentId —— 否则「不是你的任务」的响应里
            // assignedAgent 仍写着调用者自己，语义自相矛盾、误导执行者。
            result.setAssignedAgent(subTask.assignedAgentId());
            result.setOk(false);
            result.setStarted(false);
            result.setReason("not_task_owner");
            result.setStatus(subTask.status() != null ? subTask.status().name() : null);
            return result;
        }
        result.setAssignedAgent(agentId);
        // 幂等：已 IN_PROGRESS 直接成功（Agent 重试无需区分）
        if (subTask.status() == SubTaskStatus.IN_PROGRESS) {
            result.setOk(true);
            result.setStarted(true);
            result.setStatus(subTask.status().name());
            result.setVersion(subTask.version());
            return result;
        }
        // 状态白名单：REWORK→IN_PROGRESS / ASSIGNED→IN_PROGRESS / PAUSED→IN_PROGRESS 均为状态机合法转换。
        // 该白名单产出**对外 reason 码**（invalid_status:XXX），属本协议适配层的职责，故留在本层；
        // 实际写入经 SubTaskCommandPort.start（原子命令，合法性由 task 域状态机 + @Version 兜底）。
        if (subTask.status() != SubTaskStatus.ASSIGNED
                && subTask.status() != SubTaskStatus.REWORK
                && subTask.status() != SubTaskStatus.PAUSED) {
            result.setOk(false);
            result.setStarted(false);
            result.setReason("invalid_status:" + subTask.status());
            result.setStatus(subTask.status().name());
            return result;
        }

        subTaskCommandPort.start(subTaskId);
        SubTaskSnapshot updated = subTaskQueryPort.findById(subTaskId);
        result.setOk(true);
        result.setStarted(true);
        result.setStatus(updated != null && updated.status() != null
                ? updated.status().name() : SubTaskStatus.IN_PROGRESS.name());
        result.setVersion(updated != null ? updated.version() : subTask.version() + 1);
        log.info("MCP 子任务开工: subTaskId={}, agentId={}, from={}", subTaskId, agentId, subTask.status());
        return result;
    }

    /**
     * G-006 C2 外部轨迹可观测：认领成功埋点 AGENT_STARTED（外部 Agent 工作周期起点）。
     *
     * <p>坐标规则与内部 Turn 同型（run-{taskId}-{roundNum} / 1+rework+attempt，ADR-001）；
     * 事件 write-only 不参与业务决策，失败仅告警不阻断认领（ExecutionResultHandler 同款降级范式）。</p>
     */
    private void recordClaimStarted(Long taskId, Long subTaskId, SubTaskSnapshot subTask, Long agentId) {
        try {
            agentEventRecorder.record(
                    AgentEventContextResolver.resolveRunId(taskId),
                    taskId, subTaskId,
                    AgentEventContextResolver.resolveTurn(
                            subTask != null ? subTask.reworkCount() : null,
                            subTask != null ? subTask.attemptTotal() : null), 0,
                    AgentEventType.AGENT_STARTED, agentId,
                    Map.of("scenario", "claim"));
        } catch (Exception e) {
            log.warn("Agent 事件记录失败（事件 write-only，降级不阻断认领）: type={}, subTaskId={}, agentId={}, err={}",
                    AgentEventType.AGENT_STARTED, subTaskId, agentId, e.getMessage());
        }
    }

    /**
     * P1 认领准入闸门可观测：认领被拒（白名单外 / 技能不匹配）落 task_timeline。
     *
     * <p>不落此事件则「白名单外 Agent 点了认领却毫无反应」，OPS 泳道看不到拒绝节点、
     * 无法区分「被拒」与「没点」。整段 write-only 降级：失败仅告警、不阻断主链路
     * （与 {@link #recordClaimStarted} 同款范式）。</p>
     *
     * @param reason 拒绝原因码（{@code not_in_executor_whitelist} / {@code skill_not_matched}）
     */
    private void recordClaimRejected(Long taskId, Long subTaskId, Long agentId, String reason) {
        try {
            taskTimelinePort.recordEvent(taskId, subTaskId, "sub_task_claim_rejected",
                    AgentRole.SYSTEM, agentId,
                    Map.of("reason", reason, "agentId", String.valueOf(agentId)));
        } catch (Exception e) {
            log.warn("认领被拒事件记录失败（事件 write-only，降级不阻断）: subTaskId={}, agentId={}, err={}",
                    subTaskId, agentId, e.getMessage());
        }
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
        // 上方已显式 seen()，此处传 true 避免同一路径重复双写 last_seen_time。
        refreshDutyLease(agentId, true);

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
     * （平台转存 MinIO 并注册一步到位）；本工具仅适用于「对象已在平台桶内」时的
     * 登记（只注册 DB 元数据记录，不传输文件内容）。
     *
     * <p>storageUrl 格式：{@code minio://helloai-artifacts/{ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{文件名}}。
     * 协议头与 bucket 段必须是平台桶，不是 Agent 自身注册名——历史事故中外部 Agent 写成
     * {@code minio://trae-executor/...}，前缀被当成 bucket，附件只能登记、永远读不出内容。
     * 平台在 {@code AttachmentService#register} 内校验 bucket 段白名单与对象存在性，
     * 不合法直接 400。</p>
     *
     * <p>上游已有产出直接复用其 storageUrl 登记即可，无需重复上传副本。</p>
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

        Long attachmentId = attachmentPort.register(agentId, subTaskId,
                fileName, mimeType, fileSize, storageUrl);

        UploadArtifactResult result = new UploadArtifactResult();
        result.setOk(true);
        result.setAttachmentId(attachmentId);
        result.setStorageUrl(storageUrl);
        return result;
    }

    // ================================================================
    // submitResult
    // ================================================================

    @Transactional(rollbackFor = Exception.class)
    public SubmitResultResult submitResult(Long agentId, Long subTaskId, String resultId,
                                          Boolean success, String output, String error, String finishReason,
                                          Integer tokenUsage) {
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

        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("subtask_not_found");
            return r;
        }
        if (subTask.assignedAgentId() == null || !agentId.equals(subTask.assignedAgentId())) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("not_task_owner");
            return r;
        }

        if (subTask.status() == SubTaskStatus.ASSIGNED) {
            subTaskCommandPort.start(subTaskId);
            subTask = subTaskQueryPort.findById(subTaskId);
        }
        if (subTask == null || subTask.status() != SubTaskStatus.IN_PROGRESS) {
            SubmitResultResult r = new SubmitResultResult();
            r.setOk(false);
            r.setAccepted(false);
            r.setReason("invalid_status:" + (subTask != null ? subTask.status() : "null"));
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
        // B5.1（G-008 盲区收口）：外部执行者回报的 token 原样入链路（null = 旧协议，行为不变）
        report.setTokenUsage(tokenUsage);
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

        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!agentId.equals(subTask.assignedAgentId())) {
            throw new BizException("只能阻塞自己名下的子任务");
        }

        subTaskCommandPort.block(subTaskId, reason, agentId);

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

        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        List<Long> dependsOn = subTask.dependsOn();
        GetDepsSummaryResult result = new GetDepsSummaryResult();
        result.setSubTaskId(subTaskId);
        result.setTaskId(subTask.taskId());
        result.setDegraded(false);
        if (dependsOn == null || dependsOn.isEmpty()) {
            result.setDepCount(0);
            result.setLoadedCount(0);
            result.setTruncatedCount(0);
            // 无前置视为就绪（与 SubTaskService.isReady 的「空依赖恒就绪」同口径）
            result.setReady(true);
            result.setNotReadyCount(0);
            result.setDeps(Collections.emptyList());
            return result;
        }

        try {
            List<SubTaskSnapshot> deps = subTaskQueryPort.listByIds(dependsOn);
            Map<Long, SubTaskSnapshot> depMap = new HashMap<>();
            if (deps != null) {
                for (SubTaskSnapshot dep : deps) {
                    depMap.put(dep.id(), dep);
                }
            }

            List<GetDepsSummaryResult.DepItem> items = new ArrayList<>();
            int loadedCount = 0;
            int truncatedCount = 0;
            int notReadyCount = 0;
            for (Long depId : dependsOn) {
                SubTaskSnapshot dep = depMap.get(depId);
                if (dep == null) {
                    continue;
                }
                GetDepsSummaryResult.DepItem item = new GetDepsSummaryResult.DepItem();
                item.setSubTaskId(dep.id());
                item.setTitle(dep.title());
                item.setStatus(dep.status() != null ? dep.status().name() : null);
                if (dep.status() != SubTaskStatus.DONE) {
                    notReadyCount++;
                }
                String summary = taskRunningSpecPort.findExecutionSummary(subTask.taskId(), depId);
                if (summary != null && !summary.isBlank()) {
                    item.setSummary(summary);
                }
                String content = loadUpstreamContent(dep);
                if (content != null && !content.isBlank()) {
                    // P-1 防御：行边界回退截断 + 结构化 [TRUNCATED] 标注行（与核验侧同口径，
                    // 消费方可机读「哪些内容不可见」），替代原硬切 substring(0, 4000)
                    int originalChars = content.length();
                    String render = TextTruncator.truncateAtLineBoundary(content, DEP_CONTENT_MAX_CHARS);
                    if (render.length() < originalChars) {
                        render = render + "\n[TRUNCATED] shown=" + render.length()
                                + " total=" + originalChars + " reason=dep_content_limit";
                        item.setTruncated(true);
                        truncatedCount++;
                    } else if (render.contains("[TRUNCATED] file=")) {
                        // R2（2026-09-30 审计 §15.4）：附件路径逐附件截断标注已在 render 内
                        // （总长受控故顶层不触发）——布尔位与计数如实反映「存在不可见内容」
                        item.setTruncated(true);
                        truncatedCount++;
                    }
                    item.setContent(render);
                    item.setLoaded(true);
                    item.setContentChars(render.length());
                    loadedCount++;
                } else {
                    // P1-3：显式标注「未读到内容」，让消费方与「无前置」区分开
                    item.setLoaded(false);
                    item.setContentChars(0);
                }
                items.add(item);
            }
            result.setDeps(items);
            result.setDepCount(dependsOn.size());
            result.setLoadedCount(loadedCount);
            result.setTruncatedCount(truncatedCount);
            result.setReady(notReadyCount == 0);
            result.setNotReadyCount(notReadyCount);
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
     * 读取前置子任务的完成内容本体：物化附件（local:// 平台直读，仅 ACTIVE 有效版本——同名历史版本已由 {@code AttachmentService.register} 自动去活）
     * 优先，失败/无附件回退 {@code context.lastExecution.output} 原始产出；两者均无返回 null。
     * 与执行链侧的上游产出装载逻辑同源但**消费方隔离**（各自持有，避免渲染逻辑耦合）。
     *
     * <p><b>P-1 修复（2026-09-30）</b>：反转 {@code listActive} 倒序为正序（最早创建在前，
     * 主文件优先）+ 拼接全部可加载 ACTIVE 附件（各带 {@code 【文件：xxx】} 来源标题行）——
     * 原实现只取倒序第一个可加载附件，后创建的附录会把主文件挤出（tku-e2e-01 事故根因）。</p>
     *
     * <p><b>P-1 修复 R2（2026-09-30 审计 §15.4）</b>：拼接改为
     * {@link UpstreamAttachmentRenderer} 逐附件配额渲染（主附件保底 + 次要最低配额 +
     * 逐附件 {@code [TRUNCATED] file=...} 标注）——替代「拼接后单点 4000 截断」，
     * 第二个及以后附件不再整体不可见。</p>
     */
    private String loadUpstreamContent(SubTaskSnapshot dep) {
        try {
            List<AttachmentRef> attachments = attachmentPort.listActive(dep.id());
            if (attachments != null && !attachments.isEmpty()) {
                List<AttachmentRef> ordered = new ArrayList<>(attachments);
                Collections.reverse(ordered);
                List<UpstreamAttachmentRenderer.LoadedAttachment> loaded = new ArrayList<>();
                for (AttachmentRef attachment : ordered) {
                    try {
                        if (!attachment.contentLoadable()) {
                            continue;
                        }
                        byte[] bytes = attachmentPort.loadContent(attachment.id());
                        if (bytes == null || bytes.length == 0) {
                            continue;
                        }
                        String fileName = attachment.fileName() != null && !attachment.fileName().isBlank()
                                ? attachment.fileName() : "attachment-" + attachment.id();
                        loaded.add(new UpstreamAttachmentRenderer.LoadedAttachment(
                                fileName, new String(bytes, StandardCharsets.UTF_8)));
                    } catch (Exception singleEx) {
                        log.warn("读取单个前置附件失败，跳过该附件: subTaskId={}, attachmentId={}, err={}",
                                dep.id(), attachment.id(), singleEx.getMessage());
                    }
                }
                if (!loaded.isEmpty()) {
                    return UpstreamAttachmentRenderer.render(loaded, DEP_CONTENT_MAX_CHARS);
                }
            }
        } catch (Exception e) {
            log.warn("读取前置物化附件内容失败，回退原始产出: subTaskId={}, err={}", dep.id(), e.getMessage());
        }
        return SubTaskOutputExtractor.extractExecutionOutput(dep.context());
    }

    // ================================================================
    // getSubTaskDetail（子任务详情：验收标准 / 执行边界下发）
    // ================================================================

    /**
     * 查询子任务详情（执行内容 / 交付物 / 验收标准 / 约束 / 不确定性）。
     *
     * <p>缺口背景：外部执行者此前只能从收件箱摘要看到「交付物」，而审查侧轨道 A 按
     * {@code acceptance} 核验——执行者看不到验收标准却要被按验收标准判定，是独立的
     * 结构性错配。本工具把开工所需的子任务全文一次性下发，字段口径与平台内执行 Prompt
     * 的「当前任务」四要素同源，不做事后截断（截断验收标准正是缺口成因之一）。</p>
     *
     * <p>授权（最小必要）：仅「已分配给本 Agent」或「未分配且 PENDING（可认领）」的
     * 子任务可查——前者覆盖认领后开工 / 返工 / 重连，后者覆盖认领前评估；已转给他人、
     * 已回收或已终结的子任务对非归属者不可见。</p>
     */
    public SubTaskDetail getSubTaskDetail(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "getSubTaskDetail");
        refreshDutyLease(agentId); // 查看任务详情顺带续租

        SubTaskSnapshot subTask = subTaskQueryPort.findById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        boolean assignedToMe = agentId.equals(subTask.assignedAgentId());
        boolean claimable = subTask.assignedAgentId() == null
                && subTask.status() == SubTaskStatus.PENDING;
        if (!assignedToMe && !claimable) {
            throw new BizException("无权查看该子任务: subTaskId=" + subTaskId
                    + ", assignedAgentId=" + subTask.assignedAgentId()
                    + ", status=" + subTask.status());
        }
        return buildSubTaskDetail(subTask);
    }

    /**
     * 子任务快照 → 详情 DTO（claimSubTask 成功路径与 getSubTaskDetail 共用，避免两处口径漂移）。
     *
     * <p>{@code requiredSkills} 走 {@code SubTaskQueryPort#mergeSkills}（子任务级 ∪ 任务级，
     * 合并规则整体留 task 域），与执行侧注入 / 审查侧核验同一清单；提供方已保证非 null，
     * 此处仍回落空列表（防御，不阻断下发）。</p>
     */
    private SubTaskDetail buildSubTaskDetail(SubTaskSnapshot subTask) {
        SubTaskDetail detail = new SubTaskDetail();
        detail.setSubTaskId(subTask.id());
        detail.setTaskId(subTask.taskId());
        detail.setTitle(subTask.title());
        detail.setContent(subTask.content());
        detail.setDeliverable(subTask.deliverable());
        detail.setAcceptance(subTask.acceptance());
        detail.setConstraints(subTask.constraints());
        detail.setUncertainties(subTask.uncertainties());
        List<String> mergedSkills = subTaskQueryPort.mergeSkills(subTask.id());
        detail.setRequiredSkills(mergedSkills != null ? mergedSkills : Collections.emptyList());
        detail.setPriority(subTask.priority());
        detail.setStatus(subTask.status() != null ? subTask.status().name() : null);
        detail.setContract(Integer.valueOf(1).equals(subTask.isContract()));
        detail.setDependsOn(subTask.dependsOn());
        detail.setDeadline(subTask.deadline() != null ? subTask.deadline().toString() : null);
        detail.setReworkCount(subTask.reworkCount());
        detail.setAttachments(buildAttachmentItems(subTask.id()));
        detail.setContributors(buildContributors(subTask));
        return detail;
    }

    /**
     * 组装子任务贡献者清单（P1-7 产出归属可见性）。
     *
     * <p>数据源：执行记录（{@code agent_execution_record.agent_id}）∪ 当前执行者
     * （{@code sub_task.assigned_agent_id}），去重保序（当前执行者优先）。
     * 读取失败降级为「仅当前执行者」，不阻断详情下发。</p>
     */
    private List<Long> buildContributors(SubTaskSnapshot subTask) {
        LinkedHashSet<Long> contributors = new LinkedHashSet<>();
        if (subTask.assignedAgentId() != null) {
            contributors.add(subTask.assignedAgentId());
        }
        try {
            List<AgentExecutionRecord> records = agentExecutionRecordService.lambdaQuery()
                    .select(AgentExecutionRecord::getAgentId)
                    .eq(AgentExecutionRecord::getSubTaskId, subTask.id())
                    .list();
            if (records != null) {
                for (AgentExecutionRecord record : records) {
                    if (record.getAgentId() != null) {
                        contributors.add(record.getAgentId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("组装子任务贡献者清单失败（降级为仅当前执行者）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }
        return new ArrayList<>(contributors);
    }

    /**
     * 组装子任务 ACTIVE 附件清单（P1-4-b 附件可发现性）。
     *
     * <p>读取失败降级为空列表，不阻断详情下发（与事件记录 best-effort 同口径）。</p>
     */
    private List<AttachmentItem> buildAttachmentItems(Long subTaskId) {
        try {
            List<AttachmentRef> attachments = attachmentPort.listActive(subTaskId);
            if (attachments == null || attachments.isEmpty()) {
                return Collections.emptyList();
            }
            List<AttachmentItem> items = new ArrayList<>(attachments.size());
            for (AttachmentRef attachment : attachments) {
                AttachmentItem item = new AttachmentItem();
                item.setAttachmentId(attachment.id());
                item.setFileName(attachment.fileName());
                item.setFileType(attachment.fileType());
                item.setMimeType(attachment.mimeType());
                item.setFileSize(attachment.fileSize());
                item.setStatus(attachment.status() != null ? attachment.status().name() : null);
                item.setLoadable(attachment.contentLoadable());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.warn("组装子任务附件清单失败（降级为空列表）: subTaskId={}, err={}", subTaskId, e.getMessage());
            return Collections.emptyList();
        }
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
     * 工具调用自动续租 + 在岗续证（ §6.67 + E1 动态 TTL 自适应）：有 ACTIVE 租约时顺带延长
     * {@code expire_time}，长任务执行期间任何工具调用即可保活，无需 Agent 主动
     * 重做 checkIn；无 ACTIVE 租约时跳过（不自动打卡，保持 checkIn 的打卡语义）。
     *
     * <p>续约窗口由 {@code AgentDutyLeaseService.adaptiveRenew} 按当前状态动态计算：
     * 有在跑子任务用最大窗口（任务执行期稳定保活），空闲按表现分动态窗口
     * （低分短、高分长）；续约失败仅告警不阻断工具调用（顺带动作）。
     * checkIn/checkOut 不接入本方法：前者签发新租约，后者结束租约。</p>
     *
     * <p><b>G-015 B1 在岗续证</b>：持 ACTIVE 租约时顺带刷 {@code last_seen_time}
     * （经 {@link HeartbeatService#seen(Long)}）。此前只读/登记类工具（pullTasks / ack /
     * getSubTaskDetail / getDepsSummary / getAgentStatus / uploadArtifact / reportBlocked /
     * startSubTask）只续租约不刷 last_seen_time，导致「租约 ACTIVE + dbOnlineStatus OFFLINE」
     * 双视图分裂，并被健康巡检误判离线触发误重派。此处补齐「任一成功工具调用即续上连接存活证据」。</p>
     */
    private void refreshDutyLease(Long agentId) {
        refreshDutyLease(agentId, false);
    }

    /**
     * {@link #refreshDutyLease(Long)} 的重载。
     *
     * @param lastSeenJustRefreshed 调用前是否已显式刷过 last_seen_time（heartbeat 工具自身
     *                              已调 {@code seen()}，传 true 避免同一路径重复双写）
     */
    private void refreshDutyLease(Long agentId, boolean lastSeenJustRefreshed) {
        if (agentId == null) {
            return;
        }
        try {
            AgentDutyLease renewed = agentDutyLeaseService.adaptiveRenew(agentId);
            if (renewed != null && !lastSeenJustRefreshed) {
                heartbeatService.seen(agentId);
            }
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
