package com.helloai.core.task.service.impl;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.WatchdogProperties;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.RetryPolicy;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.common.util.HostNameUtils;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.RewardService;
import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.ReviewFact;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.projection.SubTaskSnapshotProjection;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.score.ImplicitScoreCalculator.ScoreResult;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.statemachine.SubTaskStateMachine;
import com.helloai.core.task.service.SubTaskLeaseService;
import com.helloai.core.task.service.SubTaskNotificationService;
import com.helloai.core.task.service.SubTaskQueryService;
import com.helloai.core.task.service.SubTaskStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务<b>状态迁移唯一入口</b>职责类（RM9-b 切分线 ③「最后抽状态机簇」，风险最高）。
 *
 * <p><b>本类承载的唯一不变量</b>：{@code sub_task.status} 的<b>全部迁移</b>都经本类写入，
 * 且都先过 {@link SubTaskStateMachine#validate}。拆分后仍只有这一条写状态路径 —— 含跨类调用
 * （{@code SubTaskLeaseService} 的过期回收经 {@link #reclaimToPending(SubTask)}）与 SQL 级原子写
 * （{@link #claimAtomic(Long, Long)}）在内（RM9-b 微调④，§4.3.2 验收口径 ii）。</p>
 *
 * <p><b>对 2026-08-23「§7.8 选项二不继续拆分」声明的正面回应</b>：该声明的顾虑是
 * 「状态流转各入口共享同一状态机与事件发布链，拆分会造成<b>事件序与事务边界在类间漂移</b>」——
 * 本笔把<b>整条状态流转链原样整体搬迁</b>到本类（{@code changeStatus} 的
 * 「读现状 → 校验 → 写上下文/租约 → 落库 → outbox → 收件箱 → 交接通知 → 领域事件 → 心跳」
 * 顺序与事务边界一字未改），故事件序与事务边界<b>仍在同一个类里</b>，声明所指的漂移风险未被引入；
 * 被剥离的只有声明中判为「无独立可测职责」的三簇，而它们<b>已各自拥有独立单测</b>
 * （{@code SubTaskServiceIsReadyTest} / {@code ...MergeSkillsTest} / {@code ...LeaseReclaimTest} /
 * {@code ...QuotaTest} / {@code ...ReworkBudgetTest} / {@code ...HandoverTest}）
 * ⇒ 原「无独立可测职责」的判断据实测不再成立。</p>
 *
 * <p><b>依赖方向（无环）</b>：本类 → {@link SubTaskNotificationService} → {@link SubTaskQueryService}；
 * 本类<b>不</b>依赖 {@link SubTaskQueryService}（行读取一律经同域 {@code SubTaskMapper} 直读），
 * 满足 §4.3.2 验收口径 (i)「状态机类不得反向依赖查询类」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskStateMachineServiceImpl implements SubTaskStateMachineService {

    /**
     * 同域 Mapper 直捅（CODE_STYLE §7.1：同域合规）。
     * 状态机「读现状 + 紧接写入」必须落在同一行锁/同一 {@code @Version} CAS 语义内，
     * 经查询类间接读会把「读」与「写」的事务语义切到两个类，故本类直读。
     */
    private final SubTaskMapper subTaskMapper;
    private final SubTaskNotificationService notificationService;
    private final AgentOutboxService agentOutboxService;
    private final HeartbeatService heartbeatService;
    private final ConcurrencyQuotaService concurrencyQuotaService;
    private final AgentDispatchProperties agentDispatchProperties;
    /** 执行租约看门狗配置（TTL / 开关）；进入 IN_PROGRESS 时写租约。 */
    private final WatchdogProperties watchdogProperties;
    private final TaskTimelineService taskTimelineService;
    /** 懒解析打破循环：AgentServiceImpl 依赖 SubTaskService（阶段五级联清理收口）。 */
    private final ObjectProvider<AgentService> agentServiceProvider;
    /** 懒解析打破循环：AttachmentServiceImpl 依赖 SubTaskService（register 归属校验）。 */
    private final ObjectProvider<AttachmentService> attachmentServiceProvider;
    /** 事件记录器（REWORK_STARTED 埋点；事件 write-only，失败仅告警不阻断返工链）。 */
    private final AgentEventRecorder agentEventRecorder;
    private final ImplicitScoreCalculator implicitScoreCalculator;
    private final RewardService rewardService;
    private final ReviewPort reviewPort;
    private final ApplicationEventPublisher applicationEventPublisher;

    // ══════════════════════════════════════════════════════════════
    //  状态迁移唯一入口
    // ══════════════════════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        changeStatus(subTaskId, newStatus, agentId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        SubTaskStatus oldStatus = subTask.getStatus();
        // 变更前执行者快照——换人/回收时需告知旧执行者任务已转移
        Long oldAgentId = subTask.getAssignedAgentId();
        SubTaskStateMachine.validate(oldStatus, newStatus);

        if (contextPatch != null && !contextPatch.isEmpty()) {
            Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
            ctx.putAll(contextPatch);
            subTask.setContext(ctx);
        }

        subTask.setStatus(newStatus);
        if (newStatus == SubTaskStatus.PENDING && agentId == null) {
            subTask.setAssignedAgentId(null);
        } else if (agentId != null) {
            subTask.setAssignedAgentId(agentId);
        }

        // 进入 IN_PROGRESS 时写执行租约（owner + lease_until = now + TTL），
        // 由 WatchdogLeaseRenewTask 周期续期、LeaseReconcilerTask 过期回收；
        // 开关关闭时不再写入，完全回归存量行为。
        if (newStatus == SubTaskStatus.IN_PROGRESS && watchdogProperties.isEnabled()) {
            subTask.setOwner(HostNameUtils.getHostName());
            subTask.setLeaseUntil(OffsetDateTime.now().plusSeconds(watchdogProperties.getTtlSeconds()));
        }

        // ⚠️ 事件序契约（与原 SubTaskServiceImpl 逐字一致，不得调整）：
        // 落库 → outbox → 收件箱 → 交接通知 → 领域事件 → 心跳
        boolean updated = subTaskMapper.updateById(subTask) > 0;
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }

        agentOutboxService.createEvent(toSnapshot(subTask), newStatus);

        // 投递收件箱通知
        notificationService.sendInboxNotification(subTask, newStatus, oldStatus);
        // 执行者变更（换人/回收）时告知旧执行者，防止误以为任务仍在名下继续干活
        notificationService.notifyAgentHandover(subTask, oldAgentId);
        notificationService.publishAssignmentEvent(subTask, newStatus);
        if (subTask.getAssignedAgentId() != null
                && (newStatus == SubTaskStatus.IN_PROGRESS || newStatus == SubTaskStatus.REVIEW)) {
            heartbeatService.active(subTask.getAssignedAgentId());
        }

        log.info("子任务状态变更: subTaskId={}, from={}, to={}, agentId={}",
                subTaskId, oldStatus, newStatus, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void claim(Long subTaskId, Long agentId) {
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.REVIEW, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void pause(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.PAUSED, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resume(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long subTaskId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() == SubTaskStatus.DONE || subTask.getStatus() == SubTaskStatus.CANCELLED) {
            throw new BizException("已完成或已取消的子任务不能再次取消");
        }
        changeStatus(subTaskId, SubTaskStatus.CANCELLED, null);
    }

    /**
     * 原子认领（PENDING → ASSIGNED 的单语句 SQL 级状态迁移）。
     *
     * <p><b>为何在本类（RM9-b 微调④）</b>：它是<b>状态写入</b>（{@code SET status='ASSIGNED'}
     * 且带 {@code status='PENDING'} 前置条件），按 §4.3.2 验收口径 (ii)「所有状态写入仍须经状态机类」
     * 归位本类；原切分线把它并入租约簇是按其「调度侧原子认领」调用场景归类，本笔按「写状态」判据订正，
     * 对外契约与 SQL 口径均不变。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean claimAtomic(Long subTaskId, Long agentId) {
        return subTaskMapper.claimAtomic(subTaskId, agentId) > 0;
    }

    /**
     * 租约过期回收的<b>状态写入</b>半段（IN_PROGRESS → PENDING + 清执行者/租约）。
     *
     * <p><b>为何在本类（RM9-b 微调④）</b>：原切分线把 {@code reclaimExpiredLeases} 整体归
     * {@code SubTaskLeaseService}，但其中包含一次状态写入，会使「状态迁移唯一入口」被稀释
     * （§4.3.2 验收口径 ii）。故把「校验 + 置状态 + 清 owner/lease + 落库」下沉本类，
     * 租约类只保留「挑候选 + 回收副作用编排（timeline / session 中断点）」，行为逐字不变。</p>
     *
     * @param subTask 已加载的过期租约实体（复用调用方实例，保持原「先捕获 oldOwner/taskId 再改写」时序）
     * @return true = 回收写入成功；false = 与 Watchdog 续期并发竞争失败（version 已变更），调用方跳过即可
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reclaimToPending(SubTask subTask) {
        // 租约回收显式走状态机校验：IN_PROGRESS → PENDING（A2.5 补充的回收转换）
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.PENDING);
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAssignedAgentId(null);
        subTask.setOwner(null);
        subTask.setLeaseUntil(null);
        return subTaskMapper.updateById(subTask) > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignNext(Long agentId, Long subTaskId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的子任务才能分配，当前状态: " + subTask.getStatus());
        }
        // E2：并发额度原子防线——FOR UPDATE 锁 agent 行，串行化同一 Agent 的并发派发；
        // 锁内重新统计在飞数判定额度（选人通过 ≠ 落库安全，杜绝并发超发窗口）。
        // 满额抛 AgentUnavailableException：不计入熔断统计，由 ResilientDispatcher
        // 走 fallback 换人；并发窗口下 fallback 内仍满额则异常冒泡，任务保持 PENDING 由定时兜底重试。
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            throw new BizException("Agent 服务不可用");
        }
        agentService.lockByIdForUpdate(agentId);
        if (agentDispatchProperties.isEnforceMaxConcurrent()
                && !concurrencyQuotaService.canAccept(agentId)) {
            throw new AgentUnavailableException(
                    "Agent 并发额度已满: agentId=" + agentId + ", subTaskId=" + subTaskId, agentId);
        }
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!allowedStatuses.contains(subTask.getStatus())) {
            throw new BizException("子任务状态不允许重新调度: subTaskId=" + subTaskId + ", status=" + subTask.getStatus());
        }

        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAssignedAgentId(null);
        boolean updated = subTaskMapper.updateById(subTask) > 0;
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }
        return subTaskMapper.selectById(subTaskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long subTaskId, Long newAgentId) {
        resetToPendingForDispatch(subTaskId, Set.of(SubTaskStatus.BLOCKED));
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, newAgentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        block(subTaskId, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId, String reason, Long reporterAgentId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.IN_PROGRESS
                && subTask.getStatus() != SubTaskStatus.ASSIGNED
                && subTask.getStatus() != SubTaskStatus.REWORK) {
            throw new BizException("只能对 IN_PROGRESS/ASSIGNED/REWORK 状态的子任务标记 BLOCKED");
        }

        Map<String, Object> patch = new HashMap<>();
        if (reason != null && !reason.isBlank()) {
            patch.put("blockedReason", reason);
        }
        if (reporterAgentId != null) {
            patch.put("blockedByAgentId", reporterAgentId);
        }
        patch.put("blockedAt", OffsetDateTime.now().toString());

        changeStatus(subTaskId, SubTaskStatus.BLOCKED, null, patch);

        SubTask updated = subTaskMapper.selectById(subTaskId);
        if (updated != null) {
            taskTimelineService.recordEvent(
                    updated.getTaskId(),
                    updated.getId(),
                    TimelineEventCode.SUB_TASK_REPORT_BLOCKED.code(),
                    AgentRole.EXECUTOR,
                    reporterAgentId,
                    Map.of(
                            "reason", reason != null ? reason : "",
                            "source", "agent_report"));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long subTaskId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        // 幂等性: 已是 DONE 状态直接返回，避免前端/重试重复调用报 DONE→DONE 错误
        if (subTask.getStatus() == SubTaskStatus.DONE) {
            log.info("complete() 幂等跳过: subTaskId={} 已是 DONE", subTaskId);
            return;
        }

        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.DONE);
        subTask.setStatus(SubTaskStatus.DONE);
        subTask.setCompleteTime(OffsetDateTime.now());

        // 隐式评分集成
        if (subTask.getAssignedAgentId() != null) {
            try {
                // §6.146：审查评分事实经 ReviewPort 收口，不直捅 review 域
                List<ReviewFact> reviews = reviewPort.listReviewFactsBySubTaskId(subTaskId);
                int blockCount = 0; // 可扩展: 从 activity_log 统计
                int timeoutCount = subTask.getTimeoutCount() != null ? subTask.getTimeoutCount() : 0;

                ScoreResult scoreResult = implicitScoreCalculator.calculate(subTask, reviews, blockCount, timeoutCount);

                // 写回评分结果
                Map<String, Object> factors = new HashMap<>();
                factors.put("timeScore", scoreResult.getFactors().getTimeScore());
                factors.put("qualityScore", scoreResult.getFactors().getQualityScore());
                factors.put("coopScore", scoreResult.getFactors().getCoopScore());
                factors.put("stabilityScore", scoreResult.getFactors().getStabilityScore());
                factors.put("efficiencyScore", scoreResult.getFactors().getEfficiencyScore());
                subTask.setScoreFactors(factors);
                subTask.setCompositeScore(scoreResult.getCompositeScore());
                subTask.setScoreGrade(scoreResult.getGrade());

                // 隐式积分奖惩
                if (scoreResult.getRewardDelta() != null && scoreResult.getRewardDelta() != 0) {
                    rewardService.addReward(
                            subTask.getAssignedAgentId(),
                            "隐式评分(" + scoreResult.getGrade() + "级)",
                            scoreResult.getRewardDelta(),
                            subTaskId);
                }

                log.info("隐式评分完成: subTaskId={}, grade={}, composite={}, delta={}",
                        subTaskId, scoreResult.getGrade(), scoreResult.getCompositeScore(),
                        scoreResult.getRewardDelta());
            } catch (Exception e) {
                log.error("隐式评分计算失败: subTaskId={}", subTaskId, e);
                // 评分失败不阻塞 DONE 状态转换
            }
        }

        subTaskMapper.updateById(subTask);
        agentOutboxService.createEvent(toSnapshot(subTask), SubTaskStatus.DONE);
        // 审查通过补发收件箱通知（携带最新一轮 review 评分/评语），
        // 外部 Agent 轮询 pullTasks 即可感知交付结果反馈
        notificationService.sendApprovedInboxNotification(subTask);

        // 闭环收尾：事务提交后异步解锁下游依赖节点 + 尝试 Task 自动收尾
        // （AFTER_COMMIT 监听在 SubTaskCompletionListener，避免与分发服务形成循环依赖）
        applicationEventPublisher.publishEvent(new SubTaskCompletedEvent(subTaskId, subTask.getTaskId()));
        log.info("子任务审查通过: subTaskId={}", subTaskId);
    }

    // ══════════════════════════════════════════════════════════════
    //  返工（驳回）链
    // ══════════════════════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public boolean rework(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        // LOG-20260904-007：自动驳回返工 = 新一轮执行尝试，
        // 计入共享预算 attempt_total（与调度重分配同源，判定语义统一收敛于
        // RetryPolicy.exceedsMax）；预算耗尽不再打回重执行，直接 DEAD_LETTER 待人工，
        // 杜绝「返工 3 次 × 重派 5 次 = 8 次实际执行」的叠加放大（坑点 3 单一权威）。
        if (!consumeReworkBudget(subTask)) {
            return false;
        }
        // 变更前执行者快照（自动驳回一般保持原执行者，防御性兼容换人）
        Long oldAgentId = subTask.getAssignedAgentId();
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setReworkCount(subTask.getReworkCount() != null ? subTask.getReworkCount() + 1 : 1);
        if (reworkAgentId != null) {
            subTask.setAssignedAgentId(reworkAgentId);
        }
        subTaskMapper.updateById(subTask);
        // 执行者变更（换人/回收）时告知旧执行者
        notificationService.notifyAgentHandover(subTask, oldAgentId);
        // 驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工 reworkById 共用本入口），
        // 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因
        notificationService.sendReworkInboxNotification(subTask);
        agentOutboxService.createEvent(toSnapshot(subTask), SubTaskStatus.REWORK);
        // REWORK_STARTED（Run 级事件 turn=0/step=0；reworkCount 已递增）
        recordReworkStartedSafely(subTask, reworkAgentId, false);
        // §6.104 打回失效：旧提交附件全部置 INACTIVE，返工必须重新上传最新版
        // （与核验/装载/打包的 listActive 可信视角闭环，旧证据不再进入下次核验）
        invalidateAttachmentsOnRework(subTaskId);
        log.info("子任务驳回返工: subTaskId={}, reworkCount={}", subTaskId, subTask.getReworkCount());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void reworkFresh(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = subTaskMapper.selectById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        // 变更前执行者快照——人工驳回改派时需告知旧执行者任务已转移
        Long oldAgentId = subTask.getAssignedAgentId();
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setReworkCount(0);
        // 语义闭环（LOG-20260904-007）：人工驳回 = 用户拍板开启新一轮，
        // 与死信重派（redispatchDeadLetter）对称重置共享预算 attempt_total ——
        // 否则改派后的重执行仍消耗旧预算，一次自动驳回即再入死信，违背人工裁定意图
        subTaskMapper.resetAttemptTotal(subTaskId, OffsetDateTime.now());
        if (reworkAgentId != null) {
            // 人工驳回改派语义 = 选执行者；与改派候选接口/死信重派同口径，防御 API 直调绕过
            AgentService agentService = agentServiceProvider.getIfAvailable();
            AgentProfileSnapshot target = agentService != null ? agentService.getProfileById(reworkAgentId) : null;
            if (target == null) {
                throw new BizException("改派目标 Agent 不存在: " + reworkAgentId);
            }
            if (target.role() != AgentRole.EXECUTOR) {
                throw new BizException("驳回改派只支持执行者（EXECUTOR）Agent，实际角色: " + target.role());
            }
            subTask.setAssignedAgentId(reworkAgentId);
        }
        // 人工已拍板：清除人工介入标记，避免前端面板残留、PENDING 兜底巡检继续跳过
        Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
        if (ctx.remove("manualIntervention") != null) {
            subTask.setContext(ctx);
        }
        subTaskMapper.updateById(subTask);
        // 执行者变更（换人/回收）时告知旧执行者
        notificationService.notifyAgentHandover(subTask, oldAgentId);
        // 人工驳回同样补发收件箱通知（携带 review 摘要，无历史时回退默认文案）
        notificationService.sendReworkInboxNotification(subTask);
        agentOutboxService.createEvent(toSnapshot(subTask), SubTaskStatus.REWORK);
        // REWORK_STARTED（Run 级事件 turn=0/step=0；reworkFresh 已重置 reworkCount）
        recordReworkStartedSafely(subTask, reworkAgentId, true);
        // 注意：Map.of 不接受 null 值，reworkAgentId 可能为 null（不换派原执行者重做），必须用 HashMap
        Map<String, Object> extra = new HashMap<>();
        extra.put("reworkCountReset", "true");
        extra.put("reworkAgentId", reworkAgentId);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                TimelineEventCode.SUB_TASK_MANUAL_REWORK_RESET.code(), AgentRole.SYSTEM, reworkAgentId, extra);
        // §6.104 打回失效：人工驳回同步让旧 ACTIVE 附件失效，新执行者重新产出后须 uploadArtifact 重建 ACTIVE
        invalidateAttachmentsOnRework(subTaskId);
        log.info("人工驳回重置返工计数: subTaskId={}, reworkCount=0, reworkAgentId={}", subTaskId, reworkAgentId);
    }

    /**
     * 返工共享预算消费（LOG-20260904-007）。
     *
     * <p>预算充足：原子累加 {@code attempt_total}（与调度重分配 {@code incrementAttemptTotal} 同款 SQL），
     * 返回 true；预算耗尽（{@code attempt_total >= max-reassign-attempts}，判定语义
     * {@link RetryPolicy#exceedsMax}）：REVIEW → DEAD_LETTER + timeline + 人工介入标记，返回 false。
     * 与 {@code SubTaskDispatchServiceImpl.checkReassignCircuitBreaker} 共用同一预算与判定语义
     * （坑点 3「单一权威」）。</p>
     *
     * @param subTask 已加载的子任务实体（attemptTotal 取自实体，避免二次查询）
     * @return true = 预算充足已累加；false = 预算耗尽已转 DEAD_LETTER
     */
    private boolean consumeReworkBudget(SubTask subTask) {
        int maxAttempts = agentDispatchProperties.getMaxReassignAttempts();
        if (maxAttempts <= 0) {
            // 熔断禁用（逃生口，与调度维度同口径，不推荐生产使用）
            return true;
        }
        int currentCount = subTask.getAttemptTotal() != null ? subTask.getAttemptTotal() : 0;
        if (RetryPolicy.exceedsMax(currentCount, maxAttempts)) {
            log.warn("返工预算熔断触发: subTaskId={}, attemptTotal={}, maxReassignAttempts={}, 子任务转入 DEAD_LETTER 待人工兜底",
                    subTask.getId(), currentCount, maxAttempts);
            try {
                changeStatus(subTask.getId(), SubTaskStatus.DEAD_LETTER, null,
                        Map.of("dead_letter_reason", "rework_budget_exceeded",
                                "attempt_total", String.valueOf(currentCount),
                                "max_reassign_attempts", String.valueOf(maxAttempts)));
                taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                        TimelineEventCode.SUB_TASK_DEAD_LETTER.code(), AgentRole.SYSTEM, null,
                        Map.of("reason", "rework_budget_exceeded",
                                "attempt_total", currentCount,
                                "max_reassign_attempts", maxAttempts));
                // §6.52 人工介入标记：与核验返工熔断（rework_limit）对称，前端据此展示人工兜底面板
                markManualIntervention(subTask.getId(), "rework_budget",
                        Map.of("attemptTotal", currentCount, "maxReassignAttempts", maxAttempts));
            } catch (Exception e) {
                log.error("返工预算熔断-转死信失败: subTaskId={}", subTask.getId(), e);
            }
            return false;
        }
        // 原子累加共享重试预算（与调度重分配同款 SQL：COALESCE(attempt_total,0)+1）
        subTaskMapper.incrementAttemptTotal(subTask.getId(), OffsetDateTime.now());
        return true;
    }

    /**
     * REWORK_STARTED 事件记录（Run 级 turn=0/step=0，事件 write-only，失败仅告警）。
     * {@code rework} / {@code reworkFresh} 共用（{@code reworkCountReset} 区分重置语义）。
     */
    private void recordReworkStartedSafely(SubTask subTask, Long reworkAgentId, boolean reworkCountReset) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reworkCount", subTask.getReworkCount());
            payload.put("reworkAgentId", reworkAgentId);
            payload.put("reworkCountReset", reworkCountReset);
            agentEventRecorder.record(
                    AgentEventContextResolver.resolveRunId(subTask.getTaskId()),
                    subTask.getTaskId(), subTask.getId(), 0, 0,
                    AgentEventType.REWORK_STARTED, subTask.getAssignedAgentId(), payload);
        } catch (Exception e) {
            log.warn("Agent 事件记录失败（事件 write-only，降级不阻断主链路）: type={}, subTaskId={}, err={}",
                    AgentEventType.REWORK_STARTED, subTask.getId(), e.getMessage());
        }
    }

    /**
     * 打回失效：将该子任务全部 ACTIVE 附件批量置 INACTIVE，
     * 与 {@code AttachmentServiceImpl.register} 的同名去活互补。
     *
     * <p>通过 {@code ObjectProvider<AttachmentService>} 懒解析打破与 AttachmentServiceImpl
     * 的构造器循环（后者依赖 {@code SubTaskService} 的 register 归属校验）；解析失败/异常一律 warn
     * 不阻断返工主链路（与 {@code sendReworkInboxNotification} 的 best-effort 哲学一致），
     * 旧证据残留可在下次核验或人工打捞时识别（状态为 INACTIVE）。</p>
     */
    private void invalidateAttachmentsOnRework(Long subTaskId) {
        try {
            AttachmentService attachmentService = attachmentServiceProvider.getIfAvailable();
            if (attachmentService == null) {
                log.warn("附件服务不可用，跳过打回失效: subTaskId={}", subTaskId);
                return;
            }
            attachmentService.invalidateBySubTask(subTaskId);
        } catch (Exception e) {
            log.warn("附件打回失效失败（不阻断返工主链路）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  人工介入标记（context 域状态标记，与状态机同属 sub_task 行状态面）
    // ══════════════════════════════════════════════════════════════

    /**
     * 人工介入标记：在子任务 context 写入 {@code manualIntervention} 标记并落 timeline。
     *
     * <p>best-effort 降级写（CODE_STYLE §7.1 单语句原子写豁免口径）：{@code selectById + updateById}
     * 对单实体的单次原子更新，无跨实体一致性诉求，故不加 {@code @Transactional}；
     * 整段 try-catch 降级——失败仅告警、不影响主链路。若后续追加第二条写操作，必须补事务注解。</p>
     *
     * <p><b>为何在本类（RM9-b 微调④）</b>：它与 {@code consumeReworkBudget}（本类）成对使用，
     * 且语义是「sub_task 行的人工介入状态标记」；留门面会迫使本类反向依赖门面成环。</p>
     */
    @Override
    public void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra) {
        try {
            SubTask fresh = subTaskMapper.selectById(subTaskId);
            if (fresh == null) {
                return;
            }
            Map<String, Object> ctx = new HashMap<>(fresh.getContext() != null ? fresh.getContext() : Map.of());
            Map<String, Object> mark = new HashMap<>();
            mark.put("reason", reason);
            mark.put("ts", OffsetDateTime.now().toString());
            if (extra != null) {
                mark.putAll(extra);
            }
            ctx.put("manualIntervention", mark);
            fresh.setContext(ctx);
            subTaskMapper.updateById(fresh);
            // 可观测：人工介入标记落 timeline（OPS 泳道），时序图可见「等待人工处置」节点；
            // 不能复用 Map.of——mark 可能含 null 值（extra 传入时）
            taskTimelineService.recordEvent(fresh.getTaskId(), subTaskId,
                    TimelineEventCode.SUB_TASK_MANUAL_INTERVENTION_REQUIRED.code(), AgentRole.SYSTEM, null,
                    new HashMap<>(mark));
            log.info("人工介入标记已写入: subTaskId={}, reason={}", subTaskId, reason);
        } catch (Exception e) {
            log.warn("人工介入标记写入失败（不影响主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * 清除人工介入标记（与 {@link #markManualIntervention} 对称）。
     *
     * <p>best-effort 降级写（CODE_STYLE §7.1 单语句原子写豁免口径）：{@code selectById + updateById}
     * 对单实体的单次原子更新，无跨实体一致性诉求，故不加 {@code @Transactional}；
     * 整段 try-catch 降级——失败仅告警、不影响主链路。无标记时静默返回（幂等，避免无谓写）。</p>
     */
    @Override
    public void clearManualIntervention(Long subTaskId) {
        try {
            SubTask fresh = subTaskMapper.selectById(subTaskId);
            if (fresh == null) {
                return;
            }
            Map<String, Object> ctx = fresh.getContext();
            if (ctx == null || !ctx.containsKey("manualIntervention")) {
                return;
            }
            Map<String, Object> copy = new HashMap<>(ctx);
            copy.remove("manualIntervention");
            fresh.setContext(copy);
            subTaskMapper.updateById(fresh);
            log.info("人工介入标记已清除: subTaskId={}", subTaskId);
        } catch (Exception e) {
            log.warn("人工介入标记清除失败（不影响主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * 子任务实体 → agent 域只读快照（映射逻辑统一收敛到 {@link SubTaskSnapshotProjection}，
     * 与只读查询端口 {@code SubTaskQueryPortAdapter} 共用一份口径，避免漂移）。
     *
     * <p><b>为什么要由提供方映射</b>：{@code AgentOutboxService} 属 agent 域，其
     * {@code createEvent} 若以 {@code task.entity.SubTask} 为形参，会造成
     * agent → task 的 §6 反向依赖。改由提供方（task）把实体映射为消费方（agent）定义的
     * {@link SubTaskSnapshot} 后传入，依赖方向变为 {@code task → agent}，<b>顺向合法</b>。</p>
     */
    private SubTaskSnapshot toSnapshot(SubTask subTask) {
        return SubTaskSnapshotProjection.toSnapshot(subTask);
    }
}
