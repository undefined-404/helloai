package com.helloai.core.agent.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.AgentSkillDeriver;
import com.helloai.core.agent.SkillCapabilityRequirements;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.command.ExecutionResultReport;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.port.AttachmentPort;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.McpToolService;
import com.helloai.core.agent.service.McpToolService.CheckInResult;
import com.helloai.core.agent.service.McpToolService.CheckOutResult;
import com.helloai.core.agent.service.McpToolService.GetAgentStatusResult;
import com.helloai.core.agent.service.McpToolService.HeartbeatResult;
import com.helloai.core.agent.service.McpToolService.ReportBlockedResult;
import com.helloai.core.agent.service.McpToolService.SubmitResultResult;
import com.helloai.core.agent.service.McpToolService.UploadArtifactResult;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.agent.skill.SkillPackageCatalog;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * MCP 工具上报簇：Agent 心跳 / 值班打卡 / 自身状态 / 产物登记 / 结果提交 / 阻塞上报。
 *
 * <p><b>来源</b>：由 {@code McpToolServiceImpl}（RM9-a，2026-10-16）按职责抽取 ——
 * 内聚依据为「<b>Agent 上报自身状态与结果</b>」，共享 {@code HeartbeatService} /
 * {@code AgentDutyLeaseService}。</p>
 *
 * <p><b>守卫不在此层</b>：{@code assertAgentActive} / {@code assertToolEnabled} /
 * {@code refreshDutyLease} 三个横切守卫仍留在门面 {@code McpToolServiceImpl}。
 * 特别地，{@code checkIn} / {@code checkOut} <b>不接入</b> {@code refreshDutyLease}
 * （前者签发新租约、后者结束租约），该差异由门面逐方法控制，本类不含守卫分支。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpAgentReportService {

    private final AgentService agentService;
    private final HeartbeatService heartbeatService;
    private final AgentDutyLeaseService agentDutyLeaseService;
    private final AttachmentPort attachmentPort;
    private final ExecutionResultHandler executionResultHandler;
    private final SubTaskQueryPort subTaskQueryPort;
    private final SubTaskCommandPort subTaskCommandPort;
    /**
     * `QA-M9-03`（2026-10-15）：checkIn 自报技能的能力校验依赖。
     *
     * <p>{@code skillPackageCatalog} 供技能包引用边（{@code requiredBaseCapabilities}）解析；
     * {@code llmProviderModelQueryService} 供模型能力谓词读取。两者只服务
     * {@code filterReportedSkillsByCapability}，与打卡主链其余部分无关。</p>
     */
    private final SkillPackageCatalog skillPackageCatalog;
    private final LlmProviderModelQueryService llmProviderModelQueryService;

    /**
     * 心跳上报。刷新 Agent 的 last_seen_time，维持在线状态。
     * 幂等，频繁调用无副作用。
     *
     * <p>响应附带当前值班租约状态（onDuty / leaseId / leaseExpiresAt /
     * remainingTtlSeconds），供 Agent 每次心跳自检续约——租约剩余时间不足时可主动
     * 重新 checkIn，避免被静默切到离岗。</p>
     *
     * <p>心跳顺带自动续租——有 ACTIVE 租约时按原 TTL 窗口延长
     * expire_time，remainingTtlSeconds 为续租后的剩余 TTL；外部 Agent 只要保持
     * 轮询本工具即可持续在岗，无需手动重做 checkIn。</p>
     *
     * <p><b>★顺序契约（RM9-a 抽取后仍成立）</b>：门面在调用本方法<b>之前</b>完成
     * {@code refreshDutyLease(agentId, true)}，故此处读到的租约已是<b>续租后</b>状态，
     * {@code remainingTtlSeconds} 语义与抽取前逐字一致。</p>
     */
    public HeartbeatResult heartbeat(Long agentId) {
        heartbeatService.seen(agentId);

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
    public UploadArtifactResult uploadArtifact(Long agentId, Long subTaskId,
                                                String fileName, String mimeType,
                                                Long fileSize, String storageUrl) {
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

    public SubmitResultResult submitResult(Long agentId, Long subTaskId, String resultId,
                                          Boolean success, String output, String error, String finishReason,
                                          Integer tokenUsage) {
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

    /**
     * 上报任务阻塞。EXECUTOR 遇到无法自行解决的阻塞时调用。
     * 内部调用 SubTaskService.block()，自动通知所有 PLANNER 排障。
     */
    public ReportBlockedResult reportBlocked(Long agentId, Long subTaskId, String reason) {
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
    public CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes,
                                 List<String> reportedSkills) {
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
     * <p><b>★能力校验（2026-10-15，`QA-M9-03` 收口）</b>：自报技能此前<b>完全绕过</b>
     * 模型能力校验——任一持牌 Agent 可经本工具把标准技能（含模型不支持的）与技能包
     * 写进 {@code agent.skills}，选人处随即按这些标签派发 ⇒ 「声明不越出模型实际能力」
     * 被本入口绕过（实测：{@code inner-deepseek-flash-excutor} 被塞了
     * {@code web-search} + {@code eng-web-research}，而其模型
     * {@code deepseek-v4-flash} 的 {@code webSearch=false}）。
     * 现改为：<b>标准技能与技能包</b>走注册链同款判据
     * （{@link AgentSkillDeriver#STANDARD_SKILLS} ∪ frontmatter
     * {@code requiredBaseCapabilities} 引用边），越界项<b>丢弃并告警</b>，
     * 自定义技能仍放行（平台侧能力声明，与模型原生能力无关）。</p>
     *
     * <p><b>作用域限定 {@code API_KEY_LLM}</b>：外部 Agent（{@code CLI_CLIENT} /
     * {@code WEB_BROWSER}）的模型由其自身掌控，平台无从判其能力（与
     * {@code agent.capabilities} 模态三键的同源口径一致）⇒ 只对内部 LLM 收口，
     * 不拦外部 Agent 的自报。</p>
     *
     * <p><b>丢弃而非抛错</b>：本方法在打卡主链上，自报越界属 Agent 侧不自知，
     * 抛错会把整个打卡打断（Agent 连不上岗）。告警保留可观测性。</p>
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
            List<String> reported = SkillNormalizer.normalizeAll(reportedSkills);
            List<String> accepted = filterReportedSkillsByCapability(agentId, agent, reported);
            LinkedHashSet<String> merged = new LinkedHashSet<>(existing);
            merged.addAll(accepted);
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

    /**
     * 按模型能力过滤自报技能（{@code QA-M9-03}）。
     *
     * <p>判据与注册链<b>同源</b>：标准技能走
     * {@link SkillCapabilityRequirements#satisfies}，技能包走
     * {@link SkillCapabilityRequirements#satisfiesPackage}（引用边），
     * 二者都由 {@code SkillCapabilityRequirements} 这一唯一实现点给出。</p>
     *
     * <p>非 {@code API_KEY_LLM} / 无 modelType / 未识别模型（{@code caps == null}）
     * ⇒ 原样放行（降级口径与注册链一致）。</p>
     */
    private List<String> filterReportedSkillsByCapability(Long agentId, Agent agent, List<String> reported) {
        if (reported == null || reported.isEmpty()) {
            return reported == null ? List.of() : reported;
        }
        if (agent == null
                || agent.getAccessType() != AgentAccessType.API_KEY_LLM
                || agent.getModelType() == null || agent.getModelType().isBlank()) {
            // 外部 Agent：平台不掌控其模型，无从判能力（不拦）
            return reported;
        }
        LlmProviderModelProfile profile =
                llmProviderModelQueryService.findCapabilityProfileByModelType(agent.getModelType());
        if (profile == null || profile.capabilities() == null) {
            // 未识别模型：降级放行（与 validateAgentSkills 的 profile==null 早返回同口径）
            return reported;
        }
        ModelCapabilities caps = profile.capabilities();
        List<SkillPackage> packages = skillPackageCatalog.healthyPackages();
        List<String> accepted = new ArrayList<>(reported.size());
        List<String> rejected = new ArrayList<>();
        for (String skill : reported) {
            boolean ok;
            if (AgentSkillDeriver.STANDARD_SKILLS.contains(skill)) {
                ok = SkillCapabilityRequirements.satisfies(skill, caps);
            } else {
                SkillPackage pkg = findPackage(packages, skill);
                // 未登记的标签视为自定义技能 → 豁免（与注册链「自定义技能豁免」同口径）
                ok = pkg == null || SkillCapabilityRequirements.satisfiesPackage(
                        pkg.requiredBaseCapabilities(), caps);
            }
            if (ok) {
                accepted.add(skill);
            } else {
                rejected.add(skill);
            }
        }
        if (!rejected.isEmpty()) {
            log.warn("checkIn 自报技能越出模型能力，已丢弃: agentId={}, modelType={}, rejected={}",
                    agentId, agent.getModelType(), rejected);
        }
        return accepted;
    }

    /** 按标签查技能包（严格匹配，不归一化——与目录层口径一致）。 */
    private SkillPackage findPackage(List<SkillPackage> packages, String label) {
        if (packages == null) {
            return null;
        }
        for (SkillPackage pkg : packages) {
            if (pkg != null && label.equals(pkg.name())) {
                return pkg;
            }
        }
        return null;
    }

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
    public CheckOutResult checkOut(Long agentId, String reason) {
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

    /**
     * 查询 Agent 自身状态（管理态 + DB 持久在线态 + 实时计算态）。
     *
     * <p>业务逻辑统一下沉到 {@link McpToolService}，MCP SSE 与 REST 别名
     * 两条通道工具矩阵完全对齐（10 工具），避免实现漂移。</p>
     */
    public GetAgentStatusResult getAgentStatus(Long agentId) {
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
        r.setServerTime(OffsetDateTime.now().toString());
        return r;
    }
}
