package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.McpAgentReportService;
import com.helloai.core.agent.service.McpReadModelService;
import com.helloai.core.agent.service.McpTaskClaimService;
import com.helloai.core.agent.service.McpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * MCP 工具核心逻辑（<b>协议门面 / 薄门面</b>）。
 * 每个方法对应一个 MCP 工具，供 Controller（REST + JSON-RPC）统一调用。
 *
 * <p><b>★RM9-a 拆分（2026-10-16）</b>：本类由 1334 行上帝类改造为<b>薄门面</b> ——
 * 只做「<b>横切守卫 + 逐方法委托</b>」，业务实现按职责迁入三个包内 {@code @Service}：</p>
 * <ul>
 *     <li>{@link McpTaskClaimService} —— 任务获取 → 确认 → 认领 → 开工（同一前置链）；</li>
 *     <li>{@link McpAgentReportService} —— Agent 上报自身状态与结果（心跳 / 打卡 / 状态 / 产物 / 提交 / 阻塞）；</li>
 *     <li>{@link McpReadModelService} —— 纯只读组装（前置产出摘要 / 子任务详情）。</li>
 * </ul>
 *
 * <p><b>★与 2026-08-23「§7.8 选项二：不继续拆分」书面声明的关系</b>（该声明见本类历史版本）：
 * 原声明的不拆理由有二 —— ① 「所有工具共享同一套 agent 活跃度守卫、工具开关与租约续期前置
 * （{@code assertAgentActive} / {@code assertToolEnabled} / {@code refreshDutyLease}），
 * 拆分后守卫逻辑被迫复制或跨类回调」；② 「按工具分组拆分只会平移行数」。
 * 本拆分对二者分别给出结论：② 成立故<b>不按工具分组</b>，改按 {@code §9.1 业务职责} 切分；
 * ① 由「<b>守卫留在门面、职责类不持有守卫</b>」消解 —— 守卫既未复制、也无需跨类回调，
 * 门面成为守卫的<b>唯一装配点</b>。该切分线（三类目标类名与承接方法）已于
 * <b>2026-10-12 经用户裁定「全部接受」</b>（`RM计划 §4.3`，属架构动作 / 协作规约 §42），
 * 故原「不继续拆分」书面声明在此<b>被后续裁定取代</b>（保留其理由供回读，不删史）。</p>
 *
 * <p><b>外部调用方零改动</b>：本类仍 {@code implements McpToolService} 且类名不变，
 * Spring 注入点与 REST / JSON-RPC 通道无感；每一笔拆分可<b>单笔 revert</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolServiceImpl implements McpToolService {

    // ---- 横切守卫依赖（留在门面，见类注释：守卫的唯一装配点） ----
    private final AgentService agentService;
    private final AgentMcpServerService agentMcpServerService;
    private final HeartbeatService heartbeatService;
    private final AgentDutyLeaseService agentDutyLeaseService;

    // ---- 职责类（业务实现，逐方法委托） ----
    private final McpTaskClaimService taskClaimService;
    private final McpAgentReportService agentReportService;
    private final McpReadModelService readModelService;

    /** 通知/评语摘要截断上限（收件箱 summary、review 摘要等）。 */
    private static final int SUMMARY_MAX_CHARS = 200;

    // ================================================================
    // pullTasks
    // ================================================================

    /**
     * 拉取 Agent 的待处理收件箱消息。
     * 只读，不标记已读。ack 才标记已读。
     */
    @Override
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
    @Override
    public PullTasksResult pullTasks(Long agentId, String role, int max, boolean includeRead) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "pullTasks");
        refreshDutyLease(agentId); // 轮询工具顺带续租，轮询期即保活期
        return taskClaimService.pullTasks(agentId, role, max, includeRead);
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
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AckResult ack(Long agentId, String messageId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "ack");
        refreshDutyLease(agentId); // 处理收件箱消息顺带续租
        return taskClaimService.ack(agentId, messageId);
    }

    // ================================================================
    // claimSubTask
    // ================================================================

    /**
     * 原子认领子任务。并发安全：使用 SubTaskService.claimAtomic（DB 条件更新）。
     *
     * @return claimed=true 表示认领成功，claimed=false 表示已被他人抢走或状态已变
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClaimSubTaskResult claimSubTask(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "claimSubTask");
        refreshDutyLease(agentId); // 认领/开工顺带续租，长任务执行期保活
        return taskClaimService.claimSubTask(agentId, subTaskId);
    }

    // ================================================================
    // startSubTask
    // ================================================================

    /**
     * 子任务开工：ASSIGNED / REWORK / PAUSED → IN_PROGRESS（P0-2 返工死锁修复）。
     *
     * <p>与其余 12 个工具守卫口径一致（Agent 须 ACTIVE + 工具须启用）；不加「逃生口」，
     * 避免返工死锁时唯一的出口也被守卫挡住。isToolEnabled 对 DEFAULT_EXECUTOR_TOOLS
     * 会自动补默认行，存量 Agent 首次调用即启用（幂等）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public StartSubTaskResult startSubTask(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "startSubTask");
        refreshDutyLease(agentId); // 开工顺带续租，与认领/提交同口径
        return taskClaimService.startSubTask(agentId, subTaskId);
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
     *
     * <p><b>★续租时机（RM9-a 抽取后不变）</b>：续租必须在<b>读取租约状态之前</b>发生，
     * 否则 {@code remainingTtlSeconds} 会退化为「续租前」的旧值。故本方法显式在委托前
     * 调 {@code refreshDutyLease(agentId, true)}（{@code true} = 心跳主链随后自会刷
     * {@code last_seen_time}，避免同路径重复双写）。</p>
     */
    @Override
    public HeartbeatResult heartbeat(Long agentId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "heartbeat");
        // 委托前续租：报告类服务读到的租约须是「续租后」状态（remainingTtlSeconds 语义）。
        // 传 true —— 心跳主链（McpAgentReportService#heartbeat）内已显式 seen()，
        // 避免同一路径重复双写 last_seen_time。
        refreshDutyLease(agentId, true);
        return agentReportService.heartbeat(agentId);
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
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadArtifactResult uploadArtifact(Long agentId, Long subTaskId,
                                                String fileName, String mimeType,
                                                Long fileSize, String storageUrl) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "uploadArtifact");
        refreshDutyLease(agentId); // 产物登记顺带续租
        return agentReportService.uploadArtifact(agentId, subTaskId, fileName, mimeType, fileSize, storageUrl);
    }

    // ================================================================
    // submitResult
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubmitResultResult submitResult(Long agentId, Long subTaskId, String resultId,
                                          Boolean success, String output, String error, String finishReason,
                                          Integer tokenUsage) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "submitResult");
        refreshDutyLease(agentId); // 结果提交顺带续租
        return agentReportService.submitResult(agentId, subTaskId, resultId, success,
                output, error, finishReason, tokenUsage);
    }

    // ================================================================
    // reportBlocked
    // ================================================================

    /**
     * 上报任务阻塞。EXECUTOR 遇到无法自行解决的阻塞时调用。
     * 内部调用 SubTaskService.block()，自动通知所有 PLANNER 排障。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportBlockedResult reportBlocked(Long agentId, Long subTaskId, String reason) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "reportBlocked");
        refreshDutyLease(agentId); // 阻塞上报顺带续租
        return agentReportService.reportBlocked(agentId, subTaskId, reason);
    }

    // ================================================================
    // checkIn （AgentHub P0-A：值班打卡）
    // ================================================================

    /**
     * Agent 打卡上班（开启值班租约）。
     *
     * <p>底层复用 {@code AgentDutyLeaseService#startLease}：
     * 事务内先关闭该 Agent 的所有旧 ACTIVE 租约，再新建一条。</p>
     *
     * <p>幂等语义：重复 checkIn 不会失败，旧 ACTIVE 租约会被关闭为 CLOSED（reason=new_lease_start），
     * 新的 sessionId 会覆盖返回。</p>
     *
     * <p><b>★本方法不接入 {@code refreshDutyLease}</b>：checkIn 本身即签发新租约
     * （与 checkOut 同口径，见 {@code refreshDutyLease} javadoc「checkIn/checkOut 不接入本方法」）。</p>
     */
    @Override
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
        return agentReportService.checkIn(agentId, workMode, maxConcurrent, ttlMinutes, reportedSkills);
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
     * <p><b>★本方法不接入 {@code refreshDutyLease}</b>：checkOut 本身即结束租约。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CheckOutResult checkOut(Long agentId, String reason) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "checkOut");
        return agentReportService.checkOut(agentId, reason);
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
    @Override
    public GetAgentStatusResult getAgentStatus(Long agentId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "getAgentStatus");
        refreshDutyLease(agentId); // 状态自检顺带续租
        return agentReportService.getAgentStatus(agentId);
    }

    // ================================================================
    // getDepsSummary（外部 Agent 主动获取前置产出摘要）
    // ================================================================

    /**
     * 获取指定子任务的直接前置产出摘要（结构化）。
     *
     * <p>执行链在 executeOnce 时已把依赖产出注入 Prompt（buildDependencySection），
     * 但外部 Agent 开工前无法主动查看——本工具提供接口层能力，数据口径与执行链同源。</p>
     */
    @Override
    public GetDepsSummaryResult getDepsSummary(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "getDepsSummary");
        refreshDutyLease(agentId); // 前置产出拉取顺带续租
        return readModelService.getDepsSummary(agentId, subTaskId);
    }

    // ================================================================
    // getSubTaskDetail（子任务详情：验收标准 / 执行边界下发）
    // ================================================================

    /**
     * 查询子任务详情（执行内容 / 交付物 / 验收标准 / 约束 / 不确定性）。
     *
     * <p>授权（最小必要）：仅「已分配给本 Agent」或「未分配且 PENDING（可认领）」的
     * 子任务可查——前者覆盖认领后开工 / 返工 / 重连，后者覆盖认领前评估；已转给他人、
     * 已回收或已终结的子任务对非归属者不可见。</p>
     */
    @Override
    public SubTaskDetail getSubTaskDetail(Long agentId, Long subTaskId) {
        assertAgentActive(agentId);
        assertToolEnabled(agentId, "getSubTaskDetail");
        refreshDutyLease(agentId); // 查看任务详情顺带续租
        return readModelService.getSubTaskDetail(agentId, subTaskId);
    }

    // ================================================================
    // helpers（横切守卫：唯一装配点，职责类不持有）
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

}
