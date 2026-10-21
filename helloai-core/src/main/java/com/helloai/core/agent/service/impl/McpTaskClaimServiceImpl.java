package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.port.SubTaskClaimConstraint;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.McpReadModelService;
import com.helloai.core.agent.service.McpTaskClaimService;
import com.helloai.core.agent.service.McpToolService.AckResult;
import com.helloai.core.agent.service.McpToolService.ClaimSubTaskResult;
import com.helloai.core.agent.service.McpToolService.PullTasksResult;
import com.helloai.core.agent.service.McpToolService.StartSubTaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具任务认领簇：拉取收件箱 / 确认 / 原子认领 / 开工。
 *
 * <p><b>来源</b>：由 {@code McpToolServiceImpl}（RM9-a，2026-10-16）按职责抽取 ——
 * 内聚依据为「<b>任务获取 → 确认 → 认领 → 开工</b>是<b>同一条前置链</b>」，
 * 共享 {@code SubTaskQueryPort} / {@code SubTaskCommandPort}。</p>
 *
 * <p><b>守卫不在此层</b>：{@code assertAgentActive} / {@code assertToolEnabled} /
 * {@code refreshDutyLease} 三个横切守卫仍留在门面 {@code McpToolServiceImpl}（单一注册点，
 * 避免拆分后守卫被迫复制或跨类回调——这正是 2026-08-23「不继续拆分」声明的顾虑所在，
 * 已用「门面保留守卫」消解）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpTaskClaimServiceImpl implements McpTaskClaimService {

    private final AgentService agentService;
    private final AgentInboxService agentInboxService;
    private final AgentMcpServerService agentMcpServerService;
    private final SubTaskQueryPort subTaskQueryPort;
    private final SubTaskCommandPort subTaskCommandPort;
    private final HeartbeatService heartbeatService;
    /** G-006 C2：外部轨迹可观测——认领成功埋点 AGENT_STARTED（事件 write-only，失败仅告警不阻断）。 */
    private final AgentEventRecorder agentEventRecorder;
    /** P1 认领准入闸门可观测：认领被拒落 task_timeline（{@code sub_task_claim_rejected}，write-only 降级）。 */
    private final TaskTimelinePort taskTimelinePort;
    /** 认领成功路径复用只读簇的详情组装（跨类唯一依赖，单向无环）。 */
    private final McpReadModelService readModelService;
    /**
     * G-006：外部执行轨迹对齐 —— 认领/开工即开启 {@code agent_execution_record} 的 attempt。
     * 此前该表只由平台内 MQ 派发链写入，外部 Agent 执行在审计/成本画像里完全空白。
     */
    private final AgentExecutionRecordService agentExecutionRecordService;

    /**
     * 拉取 Agent 的收件箱消息。
     * 只读，不标记已读。ack 才标记已读。
     *
     * @param includeRead ：true 时在未读之外附带最近已读消息（未读优先，已读按 read_time 倒序
     *                    补齐配额），每条消息带 read 状态位，供外部 Agent 轮询时区分新消息与已 ack 历史；
     *                    false（默认）保持原语义仅返回未读。
     */
    @Override
    public PullTasksResult pullTasks(Long agentId, String role, int max, boolean includeRead) {
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

    /**
     * 确认收件箱消息已处理。幂等：重复 ack 返回成功。
     *
     * @param agentId   Agent ID
     * @param messageId 格式 "inbox-{id}"
     */
    @Override
    public AckResult ack(Long agentId, String messageId) {
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

    /**
     * 原子认领子任务。并发安全：使用 SubTaskService.claimAtomic（DB 条件更新）。
     *
     * @return claimed=true 表示认领成功，claimed=false 表示已被他人抢走或状态已变
     */
    @Override
    public ClaimSubTaskResult claimSubTask(Long agentId, Long subTaskId) {
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
                // G-006：认领即开工 = 外部执行 attempt 起点（幂等，重连不重复计数）
                openExternalAttempt(subTaskId, agentId, "mcp-claim");
                SubTaskSnapshot started = subTaskQueryPort.findById(subTaskId);
                ClaimSubTaskResult result = new ClaimSubTaskResult();
                result.setOk(true);
                result.setClaimed(true);
                result.setAssignedAgent(agentId);
                result.setSubTaskId(subTaskId);
                result.setVersion(started != null ? started.version() : subTask.version() + 1);
                result.setDetail(readModelService.buildSubTaskDetail(started != null ? started : subTask));
                log.info("MCP 认领即开工（ASSIGNED→IN_PROGRESS）: subTaskId={}, agentId={}", subTaskId, agentId);
                return result;
            }
            ClaimSubTaskResult result = new ClaimSubTaskResult();
            result.setOk(true);
            result.setClaimed(true);
            result.setAssignedAgent(agentId);
            result.setSubTaskId(subTaskId);
            result.setVersion(subTask.version());
            // G-006：已在执行中的重复认领 —— attempt 缺失时补开（平台重启/记录丢失后重连仍可对齐）
            openExternalAttempt(subTaskId, agentId, "mcp-claim-resume");
            // 幂等路径同样下发详情：重连 / 重复认领后执行者仍需拿到验收标准与执行边界
            result.setDetail(readModelService.buildSubTaskDetail(subTask));
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
            // 门面已确保 agent 存在且 ACTIVE；此处为 agent 域原生实体，无需新增跨域依赖
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

        // G-006：外部执行 attempt 起点（与 AGENT_STARTED 埋点同址，审计面与事件面同时开线）
        openExternalAttempt(subTaskId, agentId, "mcp-claim");

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
        result.setDetail(readModelService.buildSubTaskDetail(updated != null ? updated : subTask));
        return result;
    }

    /**
     * 子任务开工：ASSIGNED / REWORK / PAUSED → IN_PROGRESS（P0-2 返工死锁修复）。
     *
     * <p>归属校验以服务端鉴权 agentId 为准（不信任入参）；已是 IN_PROGRESS 时幂等返回成功，
     * 使 Agent 重试调用安全。状态推进复用 {@link SubTaskCommandPort#start}，其内部经
     * task 域状态机校验合法性，并由 SubTask 的 {@code @Version} 乐观锁兜住并发。</p>
     */
    @Override
    public StartSubTaskResult startSubTask(Long agentId, Long subTaskId) {
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
        // G-006：返工/暂停后续跑同样是一次外部 attempt 起点（幂等，已有 open 行则复用）
        openExternalAttempt(subTaskId, agentId, "mcp-start");
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
     * G-006：开启外部执行 attempt（{@code agent_execution_record} RUNNING，幂等）。
     *
     * <p><b>为什么在这里开行</b>：外部 Agent 的「认领/开工」是平台唯一能观测到的执行起点，
     * 此前该表只由平台内 MQ 派发链写入 ⇒ 外部路径 0 行，成本画像与执行审计双双空白。</p>
     *
     * <p><b>失败只告警不阻断</b>：审计面缺行不应让 Agent 领不到任务（与 AGENT_STARTED
     * 埋点同款降级范式）。但这里不吞异常地静默——记 WARN 且保留 subTaskId/agentId，
     * 便于按日志补偿。</p>
     */
    private void openExternalAttempt(Long subTaskId, Long agentId, String trigger) {
        try {
            Agent agent = agentService.getById(agentId);
            agentExecutionRecordService.openExternalAttempt(
                    subTaskId, agentId,
                    agent != null ? agent.getAccessType() : null,
                    trigger,
                    agent != null ? "external:" + agent.getName() : null);
        } catch (Exception e) {
            log.warn("外部执行 attempt 开启失败（审计 write-only，不影响认领）: subTaskId={}, agentId={}, trigger={}, err={}",
                    subTaskId, agentId, trigger, e.getMessage());
        }
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
            taskTimelinePort.recordEvent(taskId, subTaskId, TimelineEventCode.SUB_TASK_CLAIM_REJECTED.code(),
                    AgentRole.SYSTEM, agentId,
                    Map.of("reason", reason, "agentId", String.valueOf(agentId)));
        } catch (Exception e) {
            log.warn("认领被拒事件记录失败（事件 write-only，降级不阻断）: subTaskId={}, agentId={}, err={}",
                    subTaskId, agentId, e.getMessage());
        }
    }

    /**
     * 解析收件箱消息 ID（{@code inbox-{id}} 或裸数字）。
     *
     * <p><b>归属说明（RM9-a）</b>：{@code RM计划 §4.3.1} 原将本方法列在「门面保留的私有守卫」行
     * （因它与守卫同处 {@code // helpers} 段）。实测其<b>唯一调用方是 ack</b>（非横切），
     * 故随 ack 一并迁入本类（私有），门面不再持有 —— 属实现层微调，不改变职责切分与对外契约。</p>
     */
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
