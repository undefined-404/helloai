package com.helloai.core.review.service.impl;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.support.ReviewChannel;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewExecutionEngine;
import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.review.quality.RepeatedFailureGate;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 子任务 LLM 自动核验服务实现（内循环核验门控）。
 *
 * <p>入口 {@link #reviewSubTask(Long, Long)}：读取子任务 title/content/deliverable/acceptance
 * + 执行产出（context.lastExecution.output），由 {@link ReviewExecutionEngine}
 * 渲染核验 Prompt 并经平台内 LLM 判定：</p>
 * <ul>
 *     <li>通过 → {@link SubTaskService#complete}（REVIEW→DONE，触发隐式评分与下游解锁）</li>
 *     <li>不通过 → {@link SubTaskService#rework}（REVIEW→REWORK，核验意见写入 context），
 *         并对 API_KEY_LLM 执行者重新下发执行命令闭合返工链</li>
 *     <li>LLM 调用失败/超时/输出不可解析 → <b>不改状态</b>，子任务停留 REVIEW 等人工兜底</li>
 * </ul>
 *
 * <p>触发点：{@code ExecutionResultHandler} 成功提交（→REVIEW）后发布
 * {@link SubTaskSubmittedForReviewEvent}，本类以 AFTER_COMMIT + @Async 异步消费，
 * 核验 LLM 调用不阻塞结果回报事务。</p>
 *
 * <p>防重：核验前检查当前状态仍为 REVIEW；reworkCount 达
 * {@code helloai.dispatch.auto-review-max-rework}（默认 3）后停留 REVIEW 等人工，
 * 不再自动打回，避免"执行→驳回→重执行"无限循环。</p>
 *
 * <p><b>§7.8 类规模拆分评审结论（2026-08-23）</b>：本类经四轮剥离后仍为
 * 核验编排强内聚汇聚点，按 §7.8 选项二书面声明不继续拆分：</p>
 * <ul>
 *     <li>已剥离：§6.136 解析器/证据装配（VerdictParser / ReviewEvidenceAssembler）、
 *         §6.142 选取职责（ReviewerPicker）、本轮执行与抽检
 *         （ReviewExecutionEngine / ReviewRecheckExecutor）；</li>
 *     <li>剩余职责：L1/L2/L3 三入口 + 防双审互斥锁 + 状态机编排（返工上限/能力与
 *         证据预检/双审共识）+ 判定落地（complete/rework/落库/timeline/返工命令）；</li>
 *     <li>不拆理由：三入口共享同一把锁与状态机决策，判定落地与编排共享 verdict 流转；
 *         继续拆分将导致依赖搬家与跨类内部状态共享，行为验证面扩大且无独立可测职责可剥。</li>
 * </ul>
 */
@Slf4j
@Service
public class SubTaskReviewServiceImpl implements SubTaskReviewService {

    /** §6.82 批次 D：核验互斥锁（防 L1/L2/L3 三路并发双审），key = review:lock:{subTaskId}
     *  （v1.2 §阶段1：setIfAbsent 自旋锁迁 Redisson RLock，key 不变兼容存量） */
    private static final String REVIEW_LOCK_PREFIX = "review:lock:";
    /** 锁 leaseTime 兜底：覆盖 LLM 调用超时窗口，显式 leaseTime 禁看门狗，崩溃残留自动过期 */
    private static final long REVIEW_LOCK_TTL_SECONDS = 120;

    private final SubTaskService subTaskService;
    private final AgentService agentService;
    /** §7.8 拆分：单次核验执行（渲染 Prompt/LLM 调用/对话流双写/判定解析）迁出为独立引擎。 */
    private final ReviewExecutionEngine reviewExecutionEngine;
    private final TaskTimelineService taskTimelineService;
    private final ExecutionCommandService executionCommandService;
    private final AgentDispatchProperties dispatchProperties;
    private final ConversationService conversationService;
    private final ReviewService reviewService;
    /** §6.82 核验互斥锁客户端（v1.2 §阶段1：Redis setIfAbsent 迁 Redisson RLock）。 */
    private final RedissonClient redissonClient;
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;
    private final VerdictParser verdictParser;
    /** §6.142 双审/抽检：选取职责收口（原 pickReviewerAgent 三段私有方法迁出）。 */
    private final ReviewerPicker reviewerPicker;
    /** §6.142 双审/抽检配置（helloai.review.*）。 */
    private final ReviewProperties reviewProperties;
    /** §6.142 双审 Reviewer 维度画像计数增量（best-effort 不阻断主链路）。 */
    private final AgentQualityProfileService agentQualityProfileService;
    /** §6.142 双审并行化：两路核验共享的专用线程池（helloai-start ReviewDualExecutorConfig）。 */
    private final Executor reviewDualExecutor;
    /** 事件记录器（REVIEW_STARTED/APPROVED/REJECTED 埋点；事件 write-only，失败仅告警）。 */
    private final AgentEventRecorder agentEventRecorder;
    /** RM12（B4 Quality Gate 泛化）：重复失败判定闸门（判定收口；事件/死信处置仍在本类）。 */
    private final RepeatedFailureGate repeatedFailureGate;

    /**
     * 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑：显式列为 Spring DI 唯一依据）。
     */
    @Autowired
    public SubTaskReviewServiceImpl(SubTaskService subTaskService,
                                    AgentService agentService,
                                    ReviewExecutionEngine reviewExecutionEngine,
                                    TaskTimelineService taskTimelineService,
                                    ExecutionCommandService executionCommandService,
                                    AgentDispatchProperties dispatchProperties,
                                    ConversationService conversationService,
                                    ReviewService reviewService,
                                    RedissonClient redissonClient,
                                    ReviewEvidenceAssembler reviewEvidenceAssembler,
                                    VerdictParser verdictParser,
                                    ReviewerPicker reviewerPicker,
                                    ReviewProperties reviewProperties,
                                    AgentQualityProfileService agentQualityProfileService,
                                    @Qualifier("reviewDualExecutor") Executor reviewDualExecutor,
                                    AgentEventRecorder agentEventRecorder,
                                    RepeatedFailureGate repeatedFailureGate) {
        this.subTaskService = subTaskService;
        this.agentService = agentService;
        this.reviewExecutionEngine = reviewExecutionEngine;
        this.taskTimelineService = taskTimelineService;
        this.executionCommandService = executionCommandService;
        this.dispatchProperties = dispatchProperties;
        this.conversationService = conversationService;
        this.reviewService = reviewService;
        this.redissonClient = redissonClient;
        this.reviewEvidenceAssembler = reviewEvidenceAssembler;
        this.verdictParser = verdictParser;
        this.reviewerPicker = reviewerPicker;
        this.reviewProperties = reviewProperties;
        this.agentQualityProfileService = agentQualityProfileService;
        this.reviewDualExecutor = reviewDualExecutor;
        this.agentEventRecorder = agentEventRecorder;
        this.repeatedFailureGate = repeatedFailureGate;
    }

    /** AFTER_COMMIT 异步监听：结果回报事务提交后触发自动核验。 */
    @Override
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmittedForReview(SubTaskSubmittedForReviewEvent event) {
        if (!dispatchProperties.isAutoReviewEnabled()) {
            log.debug("自动核验未启用，子任务停留 REVIEW 等人工: subTaskId={}", event.getSubTaskId());
            return;
        }
        try {
            reviewSubTask(event.getSubTaskId(), event.getExecutorAgentId());
        } catch (Exception e) {
            log.warn("自动核验异常，子任务停留 REVIEW 等人工兜底: subTaskId={}, err={}",
                    event.getSubTaskId(), e.getMessage());
        }
    }

    /**
     * REVIEW 孤儿兜底扫描：当 AFTER_COMMIT 事件链因线程池 / 序列化丢失时，
     * 基于 DB 状态的定期扫描作为二次确保。
     *
     * <p>扫描间隔可通过 {@code helloai.dispatch.review-orphan-scan-interval-ms} 配置（默认 30s），
     * 扫描阈值通过 {@code helloai.dispatch.review-orphan-threshold-seconds} 配置（默认 60s），
     * 表示子任务进入 REVIEW 超过该时间且无审查记录时才触发兜底核验。</p>
     */
    @Override
    @Scheduled(fixedDelayString = "${helloai.dispatch.review-orphan-scan-interval-ms:30000}")
    public void scanReviewOrphans() {
        if (!dispatchProperties.isAutoReviewEnabled()) {
            return;
        }
        int threshold = dispatchProperties.getReviewOrphanThresholdSeconds() > 0
                ? dispatchProperties.getReviewOrphanThresholdSeconds() : 60;
        int batchSize = dispatchProperties.getReviewOrphanBatchSize() > 0
                ? dispatchProperties.getReviewOrphanBatchSize() : 10;

        List<SubTaskView> orphans = subTaskService.listReviewOrphanViews(threshold, batchSize);
        if (orphans.isEmpty()) {
            return;
        }
        log.info("REVIEW 孤儿扫描发现 {} 条候选: threshold={}s, batchSize={}",
                orphans.size(), threshold, batchSize);
        for (SubTaskView st : orphans) {
            try {
                reviewSubTask(st.id(), st.assignedAgentId());
            } catch (Exception e) {
                log.warn("REVIEW 孤儿兜底核验异常: subTaskId={}, err={}", st.id(), e.getMessage());
            }
        }
    }

    /**
     * 对指定子任务执行一次 LLM 自动核验。
     *
     * <p>不加类级事务：LLM 调用耗时较长；complete/rework 各自内部事务原子提交，
     * 判定失败/不可解析时不改状态（子任务停留 REVIEW）。</p>
     *
     * <p>§6.82 批次 D 防双审互斥锁：L1 AFTER_COMMIT 事件 / L2 MQ consumer / L3 孤儿扫描
     * 三路可能并发触发同一子任务核验，Redisson RLock 保证 LLM 调用窗口内仅一路进入
     * （其他路直接跳过），显式 leaseTime 兜底崩溃残留，finally isHeldByCurrentThread
     * 防御释放（v1.2 §阶段1：setIfAbsent + 无条件 delete 迁 RLock，修掉锁过期被接管后
     * 误删他人锁的窗口）。</p>
     */
    @Override
    public void reviewSubTask(Long subTaskId, Long executorAgentId) {
        if (subTaskId == null) {
            log.debug("自动核验跳过：subTaskId 为空");
            return;
        }
        RLock lock = redissonClient.getLock(REVIEW_LOCK_PREFIX + subTaskId);
        try {
            // waitTime=0 保持"抢占失败即跳过"语义；显式 leaseTime 禁看门狗，TTL 兜底口径与旧 setIfAbsent 一致
            boolean locked = lock.tryLock(0, REVIEW_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.debug("自动核验跳过：已有核验进行中（防双审）, subTaskId={}", subTaskId);
                return;
            }
            try {
                doReview(subTaskId, executorAgentId);
            } finally {
                // isHeldByCurrentThread 防御：锁已过期被他人接管时，本线程不得释放他人持有的锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("自动核验锁获取中断，子任务停留 REVIEW 等兜底: subTaskId={}", subTaskId);
        }
    }

    /** 核验主体（互斥锁内执行，入口见 {@link #reviewSubTask(Long, Long)}）。 */
    private void doReview(Long subTaskId, Long executorAgentId) {
        SubTaskView subTask = subTaskService.getView(subTaskId);
        if (subTask == null) {
            log.warn("自动核验跳过：子任务不存在, subTaskId={}", subTaskId);
            return;
        }
        // 防重：状态必须仍为 REVIEW（可能已被人工审查推进）
        if (subTask.status() != SubTaskStatus.REVIEW) {
            log.debug("自动核验跳过：状态非 REVIEW, subTaskId={}, status={}", subTaskId, subTask.status());
            return;
        }
        // 返工次数上限：达上限后停留 REVIEW 等人工，不再自动打回
        int reworkCount = subTask.reworkCount() != null ? subTask.reworkCount() : 0;
        int maxRework = dispatchProperties.getAutoReviewMaxRework();
        if (maxRework > 0 && reworkCount >= maxRework) {
            log.warn("自动核验跳过：返工已达上限, subTaskId={}, reworkCount={}, max={}",
                    subTaskId, reworkCount, maxRework);
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_auto_review_skip_max_rework", AgentRole.REVIEWER, null,
                    Map.of("reworkCount", reworkCount, "maxRework", maxRework));
            // 核验返工熔断显式入死信：与调度维度 sub_task_dead_letter 对称，
            // 时序图 DLQ 泳道可见"熔断 → 人工打捞"，回调链路清晰
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_review_dead_letter", AgentRole.SYSTEM, null,
                    Map.of("reason", "rework_limit_exceeded",
                            "reworkCount", reworkCount, "maxRework", maxRework));
            // 状态真正转入 DEAD_LETTER 死信池（REVIEW → DEAD_LETTER，状态机已放行）：
            // 前端"死信待人工"筛选/死信池菜单据此可见，不再伪装成"审查中"卡死；
            // 人工可通过死信重派（ASSIGNED）/人工介入面板（DONE/REWORK）打捞。
            // changeStatus 副作用已核：outbox 无消费者、inbox 默认分支不通知、
            // agent 不变不触发 handover，与调度维度熔断行为一致。
            subTaskService.changeStatus(subTaskId, SubTaskStatus.DEAD_LETTER, null);
            // §6.52 人工介入标记：前端据此展示"人工介入"面板（用户选 agent 驳回改派 / 直接通过）
            subTaskService.markManualIntervention(subTaskId, "rework_limit",
                    Map.of("reworkCount", reworkCount, "maxRework", maxRework));
            // 对话流留痕（2026-10-05）：终态分支与核验正常路径一样在对话流可见，
            // 避免「对话流断档、时间线继续」导致用户看到两边对不上
            recordReviewSkipConversation(subTaskId, null,
                    "自动核验跳过：返工已达上限（reason=rework_limit_exceeded，reworkCount=" + reworkCount
                            + "，maxRework=" + maxRework + "）；子任务已转入死信池，"
                            + "等待人工处置（人工通过 / 改派执行者 / 人工驳回）。",
                    "subtask_review_skip_max_rework");
            return;
        }

        // 执行密集无能力提交者预检：提交者无本机执行能力时，产出可信度存疑，
        // 跳过自动核验（避免核验 LLM 无法辨别幻觉证据而放行），打人工介入标记等人工处置。
        Long submitterId = executorAgentId != null ? executorAgentId : subTask.assignedAgentId();
        if (dispatchProperties.isFallbackSkipExecutionDense()
                && SubTaskDispatchService.isExecutionDense(subTask)
                && submitterId != null) {
            AgentProfileSnapshot submitter = agentService.getProfileById(submitterId);
            // 现状 hasLocalExecutionCapability(null)==true 表示「不跳过」：缺失提交者（快照 null）不跳过；
            // 提交者存在但无本机执行能力才跳过（等价改写，杜绝反向误跳过）
            if (submitter != null && !submitter.localExecutionCapable()) {
                log.warn("自动核验跳过：执行密集任务由无本机能力 Agent 提交, subTaskId={}, submitterAgentId={}",
                        subTaskId, submitterId);
                taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                        "sub_task_review_skip_no_capability", AgentRole.REVIEWER, submitterId,
                        Map.of("reason", "execution_dense_submitter_no_local_capability",
                                "submitterAgentId", submitterId));
                subTaskService.markManualIntervention(subTaskId, "review_skip_execution_dense_no_capability",
                        Map.of("submitterAgentId", submitterId));
                recordReviewSkipConversation(subTaskId, submitterId,
                        "自动核验跳过：执行密集任务由无本机执行能力的 Agent 提交"
                                + "（reason=execution_dense_submitter_no_local_capability，submitterAgentId="
                                + submitterId + "），产出可信度存疑；已标记人工介入，等待人工核验处置。",
                        "subtask_review_skip_no_capability");
                return;
            }
        }

        //  证据硬检查（承 预检之后）：声称的交付物必须有物化附件/可读产出支撑。
        // 无任何产出本体（output 与附件皆空）或执行密集任务无可读物化附件时，
        // 跳过自动核验并打人工介入标记——杜绝"编造文字证据也能过初筛"（trae 1923）
        ReviewEvidenceAssembler.EvidenceCheckResult evidence = reviewEvidenceAssembler.checkEvidence(subTask);
        if (!evidence.ok()) {
            log.warn("自动核验跳过：无产出证据支撑, subTaskId={}, reason={}", subTaskId, evidence.reason());
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_review_skip_no_evidence", AgentRole.REVIEWER, submitterId,
                    Map.of("reason", evidence.reason(), "submitterAgentId", submitterId,
                            "attachmentCount", evidence.attachmentCount(),
                            "outputPresent", evidence.outputPresent()));
            subTaskService.markManualIntervention(subTaskId, "review_skip_no_evidence",
                    Map.of("reason", evidence.reason(), "submitterAgentId", submitterId,
                            "attachmentCount", evidence.attachmentCount(),
                            "outputPresent", evidence.outputPresent()));
            recordReviewSkipConversation(subTaskId, submitterId,
                    "自动核验跳过：无产出证据支撑（reason=" + evidence.reason()
                            + "，attachmentCount=" + evidence.attachmentCount()
                            + "，outputPresent=" + evidence.outputPresent() + "）；"
                            + "已标记人工介入，等待人工核验处置。",
                    "subtask_review_skip_no_evidence");
            return;
        }

        // REVIEW_STARTED（Run 级事件 turn=0/step=0）。
        // 已过返工上限/能力预检/证据硬检查，正式进入核验；审核者此刻尚未选出，agentId 留空
        try {
            agentEventRecorder.record(
                    AgentEventContextResolver.resolveRunId(subTask.taskId()),
                    subTask.taskId(), subTaskId, 0, 0,
                    AgentEventType.REVIEW_STARTED, null,
                    VerdictParser.safeMap("submitterAgentId", submitterId));
        } catch (Exception e) {
            log.warn("Agent 事件记录失败（事件 write-only，降级不阻断主链路）: type={}, subTaskId={}, err={}",
                    AgentEventType.REVIEW_STARTED, subTaskId, e.getMessage());
        }

        // §6.142 双审入口：difficulty=HIGH 且未指定 reviewerAgentId 时优先双审；
        // 候选不足 2 个降级单审（timeline 观测降级），关闭开关走既有单审链路
        if (reviewProperties.isDualReviewEnabled()
                && reviewerPicker.isDualReviewRequired(subTask.taskId())) {
            List<AgentProfileSnapshot> pair = reviewerPicker.pickDual(subTask);
            if (pair.size() == 2) {
                doDualReview(subTask, executorAgentId, pair.get(0), pair.get(1));
                return;
            }
            log.warn("双审候选不足，降级单审: subTaskId={}, available={}", subTaskId, pair.size());
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_dual_review_degraded", AgentRole.REVIEWER, null,
                    Map.of("reason", "insufficient_reviewer_candidates", "available", pair.size()));
        }

        AgentProfileSnapshot reviewer = reviewerPicker.pickSingle(subTask);
        if (reviewer == null) {
            log.warn("自动核验跳过：无可用平台内核验 Agent（REVIEWER/PLANNER 且 API_KEY_LLM），"
                    + "子任务停留 REVIEW 等人工: subTaskId={}", subTaskId);
            return;
        }

        ReviewVerdict verdict = reviewExecutionEngine.execute(subTask, reviewer);
        if (verdict != null) {
            applyVerdict(subTask, executorAgentId, reviewer, verdict);
        }
    }

    /** 判定落地：对话流结果文本 + 按判定走既有通过/驳回链（单审默认链路）。 */
    private void applyVerdict(SubTaskView subTask, Long executorAgentId, AgentProfileSnapshot reviewer, ReviewVerdict verdict) {
        applyVerdict(subTask, executorAgentId, reviewer, verdict, ReviewChannel.SINGLE, null);
    }

    /**
     * 判定落地：对话流结果文本 + 按判定走既有通过/驳回链（单审/双审共识共用）。
     *
     * @param channel          链路来源（SINGLE/DUAL），决定结果消息类型
     * @param consensusSummary 双审共识摘要（首部附加行，双审才有；单审传 null）
     */
    private void applyVerdict(SubTaskView subTask, Long executorAgentId, AgentProfileSnapshot reviewer, ReviewVerdict verdict,
                              ReviewChannel channel, String consensusSummary) {
        Long subTaskId = subTask.id();
        // 对话流：审核结果（通过/驳回 + 评分 + 问题）以可读文本单独落库，
        // 与 verdict JSON 原文互补，方便前端直接展示结论；双审附加共识摘要行
        try {
            String resultText = VerdictParser.formatReviewResult(verdict);
            if (consensusSummary != null && !consensusSummary.isBlank()) {
                resultText = consensusSummary + "\n\n" + resultText;
            }
            conversationService.addMessage(subTaskId, reviewer.id(),
                    "assistant", "agent", resultText, channel.toolName("result"));
        } catch (Exception e) {
            log.warn("核验结果对话流写入失败（不阻断核验）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        if (Boolean.TRUE.equals(verdict.getPass())) {
            subTaskService.complete(subTaskId);
            recordAutoReviewQuietly(subTaskId, reviewer.id(), ReviewResult.APPROVED, verdict);
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_auto_review_passed", AgentRole.REVIEWER, reviewer.id(),
                    VerdictParser.safeMap("score", verdict.getScore(), "comment", verdict.getComment()));
            log.info("自动核验通过: subTaskId={}, reviewerAgentId={}, score={}",
                    subTaskId, reviewer.id(), verdict.getScore());
            recordReviewEventSafely(subTask, reviewer.id(), AgentEventType.REVIEW_APPROVED, verdict, channel);
        } else {
            rejectAndRework(subTask, executorAgentId, reviewer.id(), verdict);
            recordReviewEventSafely(subTask, reviewer.id(), AgentEventType.REVIEW_REJECTED, verdict, channel);
        }
    }

    /**
     * 审核终态事件记录（REVIEW_APPROVED / REVIEW_REJECTED，Run 级 turn=0/step=0）。
     * 单审/双审共识共用同一落地口，此处统一降级封装（事件 write-only，失败仅告警）。
     */
    private void recordReviewEventSafely(SubTaskView subTask, Long reviewerAgentId, AgentEventType eventType,
                                         ReviewVerdict verdict, ReviewChannel channel) {
        try {
            agentEventRecorder.record(
                    AgentEventContextResolver.resolveRunId(subTask.taskId()),
                    subTask.taskId(), subTask.id(), 0, 0,
                    eventType, reviewerAgentId,
                    VerdictParser.safeMap("reviewerAgentId", reviewerAgentId,
                            "score", verdict.getScore(),
                            "comment", verdict.getComment(),
                            "channel", channel != null ? channel.name() : null));
        } catch (Exception e) {
            log.warn("Agent 事件记录失败（事件 write-only，降级不阻断主链路）: type={}, subTaskId={}, err={}",
                    eventType, subTask.id(), e.getMessage());
        }
    }

    /**
     * 双审编排：两个不同模型 Reviewer 在专用线程池上<b>并行</b>独立核验，按共识策略落地。
     *
     * <p>REQUIRE_BOTH（默认）：两审一致按共识走既有通过/驳回链；分歧停 REVIEW
     * 转人工介入（复用前端人工介入面板，零新增通道）。ANY：任一通过即按通过落地。
     * 任一侧核验不可判定（LLM 失败/不可解析/超时）不冒然改状态，停留 REVIEW 等人工。</p>
     *
     * <p>超时口径：两侧共用同一 deadline（{@code helloai.review.dual-review-timeout-seconds}，
     * 默认 90s，严格收进核验互斥锁 TTL 120s 内），各以剩余时间等待；超时侧判定为不可判定走
     * incomplete 路径，
     * future 不取消（LLM 调用已在途，取消无收益），残留线程自然跑完由线程池回收。</p>
     *
     * <p>落库口径：共识后仅落一条 review_record（reviewer1 为记录归属），避免
     * 双审两条 record 使执行者画像 reviewed_count 重复计数（QualityProfileUpdater
     * 按 record 逐条增量）；reviewer2 判定完整保留在对话流与 timeline payload。</p>
     */
    private void doDualReview(SubTaskView subTask, Long executorAgentId, AgentProfileSnapshot reviewer1, AgentProfileSnapshot reviewer2) {
        Long subTaskId = subTask.id();
        long timeoutMs = reviewProperties.getDualReviewTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        CompletableFuture<ReviewVerdict> future1 = CompletableFuture.supplyAsync(
                () -> reviewExecutionEngine.execute(subTask, reviewer1, ReviewChannel.DUAL), reviewDualExecutor);
        CompletableFuture<ReviewVerdict> future2 = CompletableFuture.supplyAsync(
                () -> reviewExecutionEngine.execute(subTask, reviewer2, ReviewChannel.DUAL), reviewDualExecutor);
        ReviewVerdict v1 = awaitVerdict(future1, deadline);
        ReviewVerdict v2 = awaitVerdict(future2, deadline);
        if (v1 == null || v2 == null) {
            log.warn("双审核验不完整，停留 REVIEW 等人工: subTaskId={}, verdict1Ready={}, verdict2Ready={}",
                    subTaskId, v1 != null, v2 != null);
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_dual_review_incomplete", AgentRole.REVIEWER, null,
                    Map.of("reviewer1AgentId", reviewer1.id(), "reviewer2AgentId", reviewer2.id(),
                            "verdict1Ready", v1 != null, "verdict2Ready", v2 != null));
            return;
        }
        boolean pass1 = Boolean.TRUE.equals(v1.getPass());
        boolean pass2 = Boolean.TRUE.equals(v2.getPass());
        boolean requireBoth = reviewProperties.getDualReviewConsensusPolicy()
                == ReviewProperties.DualReviewConsensusPolicy.REQUIRE_BOTH;
        // 分歧（仅 REQUIRE_BOTH 存在：一过一拒）：停 REVIEW 转人工，复用前端人工介入面板
        if (requireBoth && pass1 != pass2) {
            subTaskService.markManualIntervention(subTaskId, "reviewer_disagreement",
                    new HashMap<>(Map.of(
                            "reviewer1AgentId", reviewer1.id(), "pass1", pass1,
                            "reviewer2AgentId", reviewer2.id(), "pass2", pass2,
                            "comment1", VerdictParser.nullToEmpty(v1.getComment()),
                            "comment2", VerdictParser.nullToEmpty(v2.getComment()))));
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    "sub_task_reviewer_disagreement", AgentRole.REVIEWER, null,
                    Map.of("reviewer1AgentId", reviewer1.id(), "pass1", pass1,
                            "reviewer2AgentId", reviewer2.id(), "pass2", pass2,
                            "comment1", VerdictParser.nullToEmpty(v1.getComment()),
                            "comment2", VerdictParser.nullToEmpty(v2.getComment())));
            recordReviewerStats(reviewer1.id(), reviewer2.id(), 1, 1);
            log.warn("双审分歧，停 REVIEW 转人工: subTaskId={}, reviewer1={}(pass={}), reviewer2={}(pass={})",
                    subTaskId, reviewer1.id(), pass1, reviewer2.id(), pass2);
            recordReviewSkipConversation(subTaskId, reviewer1.id(),
                    "双审分歧（reason=reviewer_disagreement）：评审1=" + reviewer1.id() + "(pass=" + pass1
                            + ") 与 评审2=" + reviewer2.id() + "(pass=" + pass2 + ") 结论不一致；"
                            + "子任务停留 REVIEW，等待人工介入裁决。",
                    "subtask_review_skip_disagreement");
            return;
        }
        // 共识落地：REQUIRE_BOTH 一致或 ANY 至少一过即走既有链；落库取 reviewer1 判定
        // （ANY 仅 reviewer2 通过时取 v2），reviewer2 判定完整保留在对话流与 timeline payload
        ReviewVerdict chosen = pass1 ? v1 : v2;
        boolean consensusPass = requireBoth ? pass1 : (pass1 || pass2);
        // 双审共识摘要：两位评审观点 + 共识策略落成可读文本，附在结果消息首部
        String consensusSummary = "## 双审共识\n\n"
                + "- 策略: " + (requireBoth ? "REQUIRE_BOTH（两审一致才落地）" : "ANY（任一通过即落地）") + "\n"
                + "- 评审1: " + verdictSummary(v1, pass1) + "\n"
                + "- 评审2: " + verdictSummary(v2, pass2) + "\n"
                + "- 共识: " + (consensusPass ? "通过" : "驳回");
        applyVerdict(subTask, executorAgentId, reviewer1, chosen, ReviewChannel.DUAL, consensusSummary);
        recordReviewerStats(reviewer1.id(), reviewer2.id(), 1, 0);
        taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                "sub_task_dual_review_consented", AgentRole.REVIEWER, reviewer1.id(),
                Map.of("consensus", consensusPass ? "APPROVED" : "REJECTED",
                        "policy", requireBoth ? "REQUIRE_BOTH" : "ANY",
                        "reviewer1AgentId", reviewer1.id(), "reviewer2AgentId", reviewer2.id(),
                        "pass1", pass1, "pass2", pass2,
                        "score1", v1.getScore() != null ? v1.getScore() : 0,
                        "score2", v2.getScore() != null ? v2.getScore() : 0));
        log.info("双审{}: subTaskId={}, consensus={}, reviewer1={}, reviewer2={}",
                requireBoth ? "一致" : "落地", subTaskId,
                consensusPass ? "APPROVED" : "REJECTED", reviewer1.id(), reviewer2.id());
    }

    /**
     * 双审共识摘要用：单侧评审观点行（通过/驳回 + 评分，评分缺失显示 -）。
     */
    private String verdictSummary(ReviewVerdict verdict, boolean pass) {
        String score = verdict.getScore() != null ? verdict.getScore() + " / 5" : "-";
        return (pass ? "通过" : "驳回") + "（评分 " + score + "）";
    }

    /**
     * 等待单侧核验结果（双审并行：以共同 deadline 的剩余时间等待）。
     *
     * <p>超时/中断/异常均返回 null（不可判定），由调用方走既有
     * {@code sub_task_dual_review_incomplete} 路径；future 不取消：核验 LLM 调用
     * 已在途，取消无收益且会中断共享连接池，残留线程自然跑完由线程池回收。</p>
     */
    private ReviewVerdict awaitVerdict(CompletableFuture<ReviewVerdict> future, long deadline) {
        long remain = deadline - System.currentTimeMillis();
        if (remain <= 0) {
            return null;
        }
        try {
            return future.get(remain, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("双审单侧核验等待异常（按不可判定处理）: err={}", e.getMessage());
            return null;
        }
    }

    /** Reviewer 维度画像计数增量（best-effort，失败不阻断双审主链路）。 */
    private void recordReviewerStats(Long reviewer1Id, Long reviewer2Id,
                                     int reviewedDelta, int disagreementDelta) {
        try {
            agentQualityProfileService.incrementReviewerStats(reviewer1Id, reviewedDelta, disagreementDelta);
            agentQualityProfileService.incrementReviewerStats(reviewer2Id, reviewedDelta, disagreementDelta);
        } catch (Exception e) {
            log.warn("Reviewer 画像计数增量失败（不阻断双审）: err={}", e.getMessage());
        }
    }

    /** 驳回处理：核验意见写入 context，rework 累加 reworkCount，并对 API_KEY_LLM 执行者重派执行命令。 */
    private void rejectAndRework(SubTaskView subTask, Long executorAgentId, Long reviewerAgentId, ReviewVerdict verdict) {
        Long subTaskId = subTask.id();
        Long targetExecutor = executorAgentId != null ? executorAgentId : subTask.assignedAgentId();

        // 核验意见写入子任务 context（作为返工原因，供执行者/人工查看）
        List<Map<String, Object>> historySnapshot = null;
        try {
            SubTaskView fresh = subTaskService.getView(subTaskId);
            if (fresh != null) {
                Map<String, Object> ctx = new HashMap<>(fresh.context() != null ? fresh.context() : Map.of());

                // §6.41 reviewHistory 多轮累积：读已有 List，缺失时把旧 lastAutoReview 包成首轮
                List<Map<String, Object>> history = new ArrayList<>();
                Object existing = ctx.get("reviewHistory");
                if (existing instanceof List<?> existList) {
                    for (Object o : existList) {
                        if (o instanceof Map<?, ?> m) {
                            // 浅拷贝防后续 current 覆盖旧轮（Map 是引用）
                            history.add(new HashMap<>((Map<String, Object>) m));
                        }
                    }
                } else if (ctx.get("lastAutoReview") instanceof Map<?, ?> legacy) {
                    Map<String, Object> first = new HashMap<>();
                    first.put("round", 1);
                    first.put("ts", OffsetDateTime.now().toString());
                    first.put("reviewerAgentId", legacy.get("reviewerAgentId"));
                    first.put("issues", legacy.get("issues"));
                    first.put("comment", legacy.get("comment"));
                    first.put("score", legacy.get("score"));
                    first.put("executorDoneIssues", List.of());
                    history.add(first);
                }

                // append 当前轮次
                int nextRound = history.size() + 1;
                Map<String, Object> current = new HashMap<>();
                current.put("round", nextRound);
                current.put("ts", OffsetDateTime.now().toString());
                current.put("reviewerAgentId", reviewerAgentId);
                current.put("issues", verdict.getIssues());
                current.put("comment", verdict.getComment());
                current.put("score", verdict.getScore());
                current.put("executorDoneIssues", List.of());  // 留待执行回填 hook（不在本轮范围）
                // P2-4：驳回携带结构化缺失证据清单（LLM 未产出/形态非法 → 空清单，零影响）
                current.put("missingEvidence", VerdictParser.normalizeMissingEvidence(verdict.getMissingEvidence()));
                history.add(current);

                ctx.put("reviewHistory", history);
                // 旧字段保留读，写入收敛到 reviewHistory（不删 lastAutoReview 以保完全向后兼容）
                ctx.put("lastAutoReview", current);

                // 供写库后的重复失败短路判定使用（含本轮，末尾两条 = 上轮 + 本轮）
                historySnapshot = history;
                subTaskService.updateContext(subTaskId, ctx);
            }
        } catch (Exception e) {
            log.warn("核验意见写入 context 失败（不阻断返工）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        // P-1 防御（A2-2，2026-09-30）：重复失败短路——同一输入连续驳回相同结构性失败时，
        // 重派只是空转（tku-e2e-01 空转 4 轮烧 346K tokens 后仍进死信）。判定在 reviewHistory
        // 落库后、返工之前：评分连续未严格提升（主判据）或文本高度相似（兜底）→ 不触发
        // 返工重派，直接 DEAD_LETTER 待人工。
        if (dispatchProperties.isAutoReviewRepeatFailureShortCircuit()
                && shortCircuitRepeatedFailure(subTask, reviewerAgentId, historySnapshot, verdict)) {
            return;
        }

        // LOG-20260904-007：rework 返回 false = 共享预算耗尽（attempt_total 达
        // max-reassign-attempts），子任务已转 DEAD_LETTER 待人工——不再补发执行命令，
        // 避免给死信子任务触发新一轮执行尝试（执行预算已封顶）。
        if (!subTaskService.rework(subTaskId, targetExecutor)) {
            log.warn("自动核验驳回跳过执行命令补发（返工预算已耗尽，子任务转死信）: subTaskId={}, executorAgentId={}",
                    subTaskId, targetExecutor);
            return;
        }
        recordAutoReviewQuietly(subTaskId, reviewerAgentId, ReviewResult.REJECTED, verdict);
        taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                "sub_task_auto_review_rejected", AgentRole.REVIEWER, reviewerAgentId,
                VerdictParser.safeMap("score", verdict.getScore(), "issues", verdict.getIssues(),
                        "comment", verdict.getComment()));
        log.info("自动核验驳回返工: subTaskId={}, reviewerAgentId={}, issues={}",
                subTaskId, reviewerAgentId, VerdictParser.summarize(verdict.getIssues(), 200));

        // 内循环闭合：对 API_KEY_LLM 执行者重新下发执行命令，触发返工重执行
        if (targetExecutor == null) {
            return;
        }
        AgentProfileSnapshot executor = agentService.getProfileById(targetExecutor);
        if (executor == null || executor.accessType() != AgentAccessType.API_KEY_LLM) {
            log.debug("返工不自动重执行（执行者非 API_KEY_LLM 或不存在），等外部/人工链路: subTaskId={}, executorAgentId={}",
                    subTaskId, targetExecutor);
            return;
        }
        try {
            // LOG-20260904-009：requiredSkills 装箱透传
            // （task 域数据随命令正向传入执行侧，执行侧不再反向查询 task）
            // G-010：改用并集装箱（子任务级 ∪ 任务级），核验与执行同清单
            executionCommandService.createAssignedCommand(subTaskId, targetExecutor, "auto-review-rework",
                    subTaskService.mergeSkills(subTask.id()));
            log.info("返工重执行命令已下发: subTaskId={}, executorAgentId={}", subTaskId, targetExecutor);
        } catch (Exception e) {
            log.warn("返工重执行命令下发失败（子任务停留 REWORK 等兜底）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    /**
     * P-1 防御（A2-2；R1 修订 2026-09-30 §15.2；<b>2026-10-03 二次修订</b>）：重复失败短路判定与处置。
     *
     * <p>判定（reviewHistory 末尾两条 = 上轮 + 本轮，先决条件两轮 issues 均非空）：</p>
     * <ul>
     *     <li><b>主判据（2026-10-03 双条件）</b>：「本轮评分未严格提升（curr ≤ prev）」<b>且</b>
     *     「两轮 issues 字符 bigram 相似度 ≥ {@code helloai.dispatch.auto-review-repeat-failure-similarity}」
     *     两者<b>同时成立</b>才判「同一结构性失败」。<br>
     *     <b>为什么加相似度第二条件</b>：原「评分未提升」单判据过强——评分是 1~5 粗粒度，
     *     连续 2→2 会掩盖「问题性质已变」的实质进展。真机 687 连续两次误伤：round6
     *     （附件截断 → 代码自洽矛盾，similarity 0.17）与 round8（→ 契约未物化，similarity 0.24）
     *     均为<b>全新问题</b>，却被 score 停滞误判为「重复失败」送死信，各靠一次人工打捞才救回。<br>
     *     <b>代价与兜底</b>：真停滞（tku-e2e-01 形态：评分恒定 2→2 且 issues 整篇重写、
     *     相似度仅 0.18~0.25）不再被本判定短路，会多空转若干轮——由其上的返工预算
     *     （{@code autoReviewMaxRework}）与共享重试预算（{@code attempt_total}）兜底封顶，
     *     不会无限循环。</li>
     *     <li><b>兜底（score 任一轮缺失）</b>：退回两轮 issues 字符 bigram 相似度
     *     ≥ {@code helloai.dispatch.auto-review-repeat-failure-similarity}（默认 0.85）——
     *     无法判「评分是否提升」时，只能以文本重复度为准。</li>
     * </ul>
     *
     * <p>处置（复用 §6.52 熔断范式，与 {@link #doReview} 的 rework_limit 熔断对称）：
     * 跳过事件 → 显式入死信事件 → 判决落 review_record → {@code changeStatus(DEAD_LETTER)}
     * → 人工介入标记；返回 true 表示已短路（调用方不再走返工重派）。事件 / 死信 / 人工
     * 介入 payload 均携带 {@code basis}（score_stall_and_text_repeat / text_repeat）与 {@code scoreTrend}
     * 证据，DLQ 泳道可机读归因。</p>
     */
    private boolean shortCircuitRepeatedFailure(SubTaskView subTask, Long reviewerAgentId,
                                                List<Map<String, Object>> history, ReviewVerdict verdict) {
        // RM12 收口：判定移交 RepeatedFailureGate（判定逻辑逐字未变，仅位置收口）；
        // 处置（事件 / 死信 / 人工介入 / 状态流转）仍留此处——涉及业务对象，属编排职责。
        GateDecision decision = repeatedFailureGate.evaluate(history);
        if (!decision.blocks()) {
            return false;
        }
        String basis = String.valueOf(decision.evidence("basis"));
        String scoreTrend = String.valueOf(decision.evidence("scoreTrend"));
        double similarity = asDouble(decision.evidence("similarity"));
        double threshold = asDouble(decision.evidence("threshold"));
        String currIssues = String.valueOf(decision.evidence("issues"));

        Long subTaskId = subTask.id();
        int round = decision.evidence("round") instanceof Number n ? n.intValue() : history.size();
        int reworkCount = subTask.reworkCount() != null ? subTask.reworkCount() : 0;
        log.warn("自动核验重复失败短路：判结构性失败转死信, subTaskId={}, basis={}, round={}, score={}, similarity={}, threshold={}",
                subTaskId, basis, round, scoreTrend, String.format("%.3f", similarity), threshold);
        taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                "sub_task_auto_review_skip_repeated_failure", AgentRole.REVIEWER, reviewerAgentId,
                Map.of("reason", "repeated_failure_signature",
                        "basis", basis,
                        "round", round,
                        "scoreTrend", scoreTrend,
                        "similarity", similarity,
                        "threshold", threshold,
                        "issues", VerdictParser.summarize(currIssues, 200)));
        // 与调度/返工上限熔断对称：显式入死信事件，DLQ 泳道"短路 → 人工打捞"可见
        taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                "sub_task_review_dead_letter", AgentRole.SYSTEM, null,
                Map.of("reason", "repeated_failure_signature",
                        "basis", basis,
                        "round", round,
                        "scoreTrend", scoreTrend,
                        "similarity", similarity,
                        "reworkCount", reworkCount));
        // 判决照常落 review_record（REJECTED），保留完整留痕供人工复核
        recordAutoReviewQuietly(subTaskId, reviewerAgentId, ReviewResult.REJECTED, verdict);
        subTaskService.changeStatus(subTaskId, SubTaskStatus.DEAD_LETTER, null);
        subTaskService.markManualIntervention(subTaskId, "repeated_failure_signature",
                Map.of("basis", basis, "round", round, "scoreTrend", scoreTrend,
                        "similarity", similarity, "threshold", threshold));
        recordReviewSkipConversation(subTaskId, reviewerAgentId,
                "自动核验跳过：重复失败熔断（reason=repeated_failure_signature，basis=" + basis
                        + "，round=" + round + "，scoreTrend=" + scoreTrend
                        + "，similarity=" + String.format("%.3f", similarity)
                        + "，threshold=" + threshold + "）；子任务已转入死信池，等待人工处置。",
                "subtask_review_skip_repeated_failure");
        return true;
    }

    /** 证据值转 double（非 Number 时取 0）。 */
    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0d;
    }

    /**
     * 核验「跳过 / 终态」分支的对话流留痕（2026-10-05 补做）。
     *
     * <p>背景：本类 5 个终态分支（返工超上限 / 执行密集无能力 / 无产出证据 / 双审分歧 / 重复失败熔断）
     * 此前只写 {@code taskTimelineService} 与 {@code markManualIntervention}，<b>不写对话流</b>，
     * 导致终态分支上「对话流断档、时间线继续」，用户看到两边对不上。本方法让这些分支与核验正常
     * 路径（{@link #applyVerdict}）一样在对话流可见。</p>
     *
     * <p><b>toolName 硬约束</b>：必须以 {@code subtask_review} 为前缀——前端
     * {@code SubTaskDetail.vue} 的 {@code convRounds} 用 {@code tool.startsWith('subtask_review')}
     * 判定核验轮次；否则这些消息会被错误归入执行轮次。</p>
     *
     * <p><b>write-only 纪律</b>：失败仅告警，绝不阻断核验主链路（与
     * {@link #applyVerdict} 的对话流写入同口径）。仅新增留痕，不改动任何判定 / 状态流转 / 事件语义。</p>
     */
    private void recordReviewSkipConversation(Long subTaskId, Long agentId, String content, String toolName) {
        try {
            conversationService.addMessage(subTaskId, agentId, "assistant", "agent", content, toolName);
        } catch (Exception e) {
            log.warn("核验跳过对话流写入失败（不阻断核验）: subTaskId={}, toolName={}, err={}",
                    subTaskId, toolName, e.getMessage());
        }
    }

    /** 自动核验落 review_record（仅记录；失败不阻断主链路）。score 缺失时按判定结果兜底并限幅 1~5。 */
    private void recordAutoReviewQuietly(Long subTaskId, Long reviewerAgentId,
                                          ReviewResult result, ReviewVerdict verdict) {
        try {
            int fallback = result == ReviewResult.APPROVED ? 3 : 1;
            int score = verdict.getScore() != null ? verdict.getScore() : fallback;
            score = Math.max(1, Math.min(5, score));
            reviewService.recordAutoReview(subTaskId, reviewerAgentId, result, score,
                    verdict.getIssues(), verdict.getComment());
        } catch (Exception e) {
            log.warn("自动核验落 review_record 失败（不阻断主链路）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    /**
     * 解析核验判定 JSON；不可解析返回 null（调用方据此停留 REVIEW）。
     * 解析逻辑委托 {@link VerdictParser}（fence 剥离 + 未转义反斜杠修复）。
     */
    @Override
    public ReviewVerdict parseVerdict(String rawOutput) {
        return verdictParser.parseVerdict(rawOutput);
    }
}
