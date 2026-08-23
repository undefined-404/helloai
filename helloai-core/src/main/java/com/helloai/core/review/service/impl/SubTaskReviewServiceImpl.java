package com.helloai.core.review.service.impl;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewExecutionEngine;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    /** §6.82 批次 D：核验互斥锁（防 L1/L2/L3 三路并发双审），key = review:lock:{subTaskId} */
    private static final String REVIEW_LOCK_PREFIX = "review:lock:";
    /** 锁 TTL 兜底：覆盖 LLM 调用超时窗口，崩溃残留自动过期 */
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
    private final StringRedisTemplate redis;
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
                                    StringRedisTemplate redis,
                                    ReviewEvidenceAssembler reviewEvidenceAssembler,
                                    VerdictParser verdictParser,
                                    ReviewerPicker reviewerPicker,
                                    ReviewProperties reviewProperties,
                                    AgentQualityProfileService agentQualityProfileService,
                                    @Qualifier("reviewDualExecutor") Executor reviewDualExecutor) {
        this.subTaskService = subTaskService;
        this.agentService = agentService;
        this.reviewExecutionEngine = reviewExecutionEngine;
        this.taskTimelineService = taskTimelineService;
        this.executionCommandService = executionCommandService;
        this.dispatchProperties = dispatchProperties;
        this.conversationService = conversationService;
        this.reviewService = reviewService;
        this.redis = redis;
        this.reviewEvidenceAssembler = reviewEvidenceAssembler;
        this.verdictParser = verdictParser;
        this.reviewerPicker = reviewerPicker;
        this.reviewProperties = reviewProperties;
        this.agentQualityProfileService = agentQualityProfileService;
        this.reviewDualExecutor = reviewDualExecutor;
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

        List<SubTask> orphans = subTaskService.listReviewOrphans(threshold, batchSize);
        if (orphans.isEmpty()) {
            return;
        }
        log.info("REVIEW 孤儿扫描发现 {} 条候选: threshold={}s, batchSize={}",
                orphans.size(), threshold, batchSize);
        for (SubTask st : orphans) {
            try {
                reviewSubTask(st.getId(), st.getAssignedAgentId());
            } catch (Exception e) {
                log.warn("REVIEW 孤儿兜底核验异常: subTaskId={}, err={}", st.getId(), e.getMessage());
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
     * 三路可能并发触发同一子任务核验，Redis setIfAbsent 保证 LLM 调用窗口内仅一路进入
     * （其他路直接跳过），TTL 兜底崩溃残留，finally 释放。</p>
     */
    @Override
    public void reviewSubTask(Long subTaskId, Long executorAgentId) {
        if (subTaskId == null) {
            log.debug("自动核验跳过：subTaskId 为空");
            return;
        }
        Boolean locked = redis.opsForValue().setIfAbsent(
                REVIEW_LOCK_PREFIX + subTaskId, "1", REVIEW_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("自动核验跳过：已有核验进行中（防双审）, subTaskId={}", subTaskId);
            return;
        }
        try {
            doReview(subTaskId, executorAgentId);
        } finally {
            redis.delete(REVIEW_LOCK_PREFIX + subTaskId);
        }
    }

    /** 核验主体（互斥锁内执行，入口见 {@link #reviewSubTask(Long, Long)}）。 */
    private void doReview(Long subTaskId, Long executorAgentId) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            log.warn("自动核验跳过：子任务不存在, subTaskId={}", subTaskId);
            return;
        }
        // 防重：状态必须仍为 REVIEW（可能已被人工审查推进）
        if (subTask.getStatus() != SubTaskStatus.REVIEW) {
            log.debug("自动核验跳过：状态非 REVIEW, subTaskId={}, status={}", subTaskId, subTask.getStatus());
            return;
        }
        // 返工次数上限：达上限后停留 REVIEW 等人工，不再自动打回
        int reworkCount = subTask.getReworkCount() != null ? subTask.getReworkCount() : 0;
        int maxRework = dispatchProperties.getAutoReviewMaxRework();
        if (maxRework > 0 && reworkCount >= maxRework) {
            log.warn("自动核验跳过：返工已达上限, subTaskId={}, reworkCount={}, max={}",
                    subTaskId, reworkCount, maxRework);
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_skip_max_rework", AgentRole.REVIEWER, null,
                    Map.of("reworkCount", reworkCount, "maxRework", maxRework));
            // 核验返工熔断显式入死信：与调度维度 sub_task_dead_letter 对称，
            // 时序图 DLQ 泳道可见"熔断 → 人工打捞"，回调链路清晰
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_review_dead_letter", AgentRole.SYSTEM, null,
                    Map.of("reason", "rework_limit_exceeded",
                            "reworkCount", reworkCount, "maxRework", maxRework));
            // §6.52 人工介入标记：前端据此展示"人工介入"面板（用户选 agent 驳回改派 / 直接通过）
            subTaskService.markManualIntervention(subTaskId, "rework_limit",
                    Map.of("reworkCount", reworkCount, "maxRework", maxRework));
            return;
        }

        // 执行密集无能力提交者预检：提交者无本机执行能力时，产出可信度存疑，
        // 跳过自动核验（避免核验 LLM 无法辨别幻觉证据而放行），打人工介入标记等人工处置。
        Long submitterId = executorAgentId != null ? executorAgentId : subTask.getAssignedAgentId();
        if (dispatchProperties.isFallbackSkipExecutionDense()
                && SubTaskDispatchService.isExecutionDense(subTask)
                && submitterId != null) {
            Agent submitter = agentService.getById(submitterId);
            if (!SubTaskDispatchService.hasLocalExecutionCapability(submitter)) {
                log.warn("自动核验跳过：执行密集任务由无本机能力 Agent 提交, subTaskId={}, submitterAgentId={}",
                        subTaskId, submitterId);
                taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                        "sub_task_review_skip_no_capability", AgentRole.REVIEWER, submitterId,
                        Map.of("reason", "execution_dense_submitter_no_local_capability",
                                "submitterAgentId", submitterId));
                subTaskService.markManualIntervention(subTaskId, "review_skip_execution_dense_no_capability",
                        Map.of("submitterAgentId", submitterId));
                return;
            }
        }

        //  证据硬检查（承 预检之后）：声称的交付物必须有物化附件/可读产出支撑。
        // 无任何产出本体（output 与附件皆空）或执行密集任务无可读物化附件时，
        // 跳过自动核验并打人工介入标记——杜绝"编造文字证据也能过初筛"（trae 1923）
        ReviewEvidenceAssembler.EvidenceCheckResult evidence = reviewEvidenceAssembler.checkEvidence(subTask);
        if (!evidence.ok()) {
            log.warn("自动核验跳过：无产出证据支撑, subTaskId={}, reason={}", subTaskId, evidence.reason());
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_review_skip_no_evidence", AgentRole.REVIEWER, submitterId,
                    Map.of("reason", evidence.reason(), "submitterAgentId", submitterId,
                            "attachmentCount", evidence.attachmentCount(),
                            "outputPresent", evidence.outputPresent()));
            subTaskService.markManualIntervention(subTaskId, "review_skip_no_evidence",
                    Map.of("reason", evidence.reason(), "submitterAgentId", submitterId,
                            "attachmentCount", evidence.attachmentCount(),
                            "outputPresent", evidence.outputPresent()));
            return;
        }

        // §6.142 双审入口：difficulty=HIGH 且未指定 reviewerAgentId 时优先双审；
        // 候选不足 2 个降级单审（timeline 观测降级），关闭开关走既有单审链路
        if (reviewProperties.isDualReviewEnabled()
                && reviewerPicker.isDualReviewRequired(subTask.getTaskId())) {
            List<Agent> pair = reviewerPicker.pickDual(subTask);
            if (pair.size() == 2) {
                doDualReview(subTask, executorAgentId, pair.get(0), pair.get(1));
                return;
            }
            log.warn("双审候选不足，降级单审: subTaskId={}, available={}", subTaskId, pair.size());
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_dual_review_degraded", AgentRole.REVIEWER, null,
                    Map.of("reason", "insufficient_reviewer_candidates", "available", pair.size()));
        }

        Agent reviewer = reviewerPicker.pickSingle(subTask);
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

    /** 判定落地：对话流结果文本 + 按判定走既有通过/驳回链（单审/双审共识共用）。 */
    private void applyVerdict(SubTask subTask, Long executorAgentId, Agent reviewer, ReviewVerdict verdict) {
        Long subTaskId = subTask.getId();
        // 对话流：审核结果（通过/驳回 + 评分 + 问题）以可读文本单独落库，
        // 与 verdict JSON 原文互补，方便前端直接展示结论
        try {
            String resultText = VerdictParser.formatReviewResult(verdict);
            conversationService.addMessage(subTaskId, reviewer.getId(),
                    "assistant", "agent", resultText, "subtask_review_result");
        } catch (Exception e) {
            log.warn("核验结果对话流写入失败（不阻断核验）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        if (Boolean.TRUE.equals(verdict.getPass())) {
            subTaskService.complete(subTaskId);
            recordAutoReviewQuietly(subTaskId, reviewer.getId(), ReviewResult.APPROVED, verdict);
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_passed", AgentRole.REVIEWER, reviewer.getId(),
                    VerdictParser.safeMap("score", verdict.getScore(), "comment", verdict.getComment()));
            log.info("自动核验通过: subTaskId={}, reviewerAgentId={}, score={}",
                    subTaskId, reviewer.getId(), verdict.getScore());
        } else {
            rejectAndRework(subTask, executorAgentId, reviewer.getId(), verdict);
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
    private void doDualReview(SubTask subTask, Long executorAgentId, Agent reviewer1, Agent reviewer2) {
        Long subTaskId = subTask.getId();
        long timeoutMs = reviewProperties.getDualReviewTimeoutSeconds() * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        CompletableFuture<ReviewVerdict> future1 = CompletableFuture.supplyAsync(
                () -> reviewExecutionEngine.execute(subTask, reviewer1), reviewDualExecutor);
        CompletableFuture<ReviewVerdict> future2 = CompletableFuture.supplyAsync(
                () -> reviewExecutionEngine.execute(subTask, reviewer2), reviewDualExecutor);
        ReviewVerdict v1 = awaitVerdict(future1, deadline);
        ReviewVerdict v2 = awaitVerdict(future2, deadline);
        if (v1 == null || v2 == null) {
            log.warn("双审核验不完整，停留 REVIEW 等人工: subTaskId={}, verdict1Ready={}, verdict2Ready={}",
                    subTaskId, v1 != null, v2 != null);
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_dual_review_incomplete", AgentRole.REVIEWER, null,
                    Map.of("reviewer1AgentId", reviewer1.getId(), "reviewer2AgentId", reviewer2.getId(),
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
                            "reviewer1AgentId", reviewer1.getId(), "pass1", pass1,
                            "reviewer2AgentId", reviewer2.getId(), "pass2", pass2,
                            "comment1", VerdictParser.nullToEmpty(v1.getComment()),
                            "comment2", VerdictParser.nullToEmpty(v2.getComment()))));
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_reviewer_disagreement", AgentRole.REVIEWER, null,
                    Map.of("reviewer1AgentId", reviewer1.getId(), "pass1", pass1,
                            "reviewer2AgentId", reviewer2.getId(), "pass2", pass2,
                            "comment1", VerdictParser.nullToEmpty(v1.getComment()),
                            "comment2", VerdictParser.nullToEmpty(v2.getComment())));
            recordReviewerStats(reviewer1.getId(), reviewer2.getId(), 1, 1);
            log.warn("双审分歧，停 REVIEW 转人工: subTaskId={}, reviewer1={}(pass={}), reviewer2={}(pass={})",
                    subTaskId, reviewer1.getId(), pass1, reviewer2.getId(), pass2);
            return;
        }
        // 共识落地：REQUIRE_BOTH 一致或 ANY 至少一过即走既有链；落库取 reviewer1 判定
        // （ANY 仅 reviewer2 通过时取 v2），reviewer2 判定完整保留在对话流与 timeline payload
        ReviewVerdict chosen = pass1 ? v1 : v2;
        boolean consensusPass = requireBoth ? pass1 : (pass1 || pass2);
        applyVerdict(subTask, executorAgentId, reviewer1, chosen);
        recordReviewerStats(reviewer1.getId(), reviewer2.getId(), 1, 0);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                "sub_task_dual_review_consented", AgentRole.REVIEWER, reviewer1.getId(),
                Map.of("consensus", consensusPass ? "APPROVED" : "REJECTED",
                        "policy", requireBoth ? "REQUIRE_BOTH" : "ANY",
                        "reviewer1AgentId", reviewer1.getId(), "reviewer2AgentId", reviewer2.getId(),
                        "pass1", pass1, "pass2", pass2,
                        "score1", v1.getScore() != null ? v1.getScore() : 0,
                        "score2", v2.getScore() != null ? v2.getScore() : 0));
        log.info("双审{}: subTaskId={}, consensus={}, reviewer1={}, reviewer2={}",
                requireBoth ? "一致" : "落地", subTaskId,
                consensusPass ? "APPROVED" : "REJECTED", reviewer1.getId(), reviewer2.getId());
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
    private void rejectAndRework(SubTask subTask, Long executorAgentId, Long reviewerAgentId, ReviewVerdict verdict) {
        Long subTaskId = subTask.getId();
        Long targetExecutor = executorAgentId != null ? executorAgentId : subTask.getAssignedAgentId();

        // 核验意见写入子任务 context（作为返工原因，供执行者/人工查看）
        try {
            SubTask fresh = subTaskService.getById(subTaskId);
            if (fresh != null) {
                Map<String, Object> ctx = new HashMap<>(fresh.getContext() != null ? fresh.getContext() : Map.of());

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
                history.add(current);

                ctx.put("reviewHistory", history);
                // 旧字段保留读，写入收敛到 reviewHistory（不删 lastAutoReview 以保完全向后兼容）
                ctx.put("lastAutoReview", current);

                fresh.setContext(ctx);
                subTaskService.updateById(fresh);
            }
        } catch (Exception e) {
            log.warn("核验意见写入 context 失败（不阻断返工）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        subTaskService.rework(subTaskId, targetExecutor);
        recordAutoReviewQuietly(subTaskId, reviewerAgentId, ReviewResult.REJECTED, verdict);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                "sub_task_auto_review_rejected", AgentRole.REVIEWER, reviewerAgentId,
                VerdictParser.safeMap("score", verdict.getScore(), "issues", verdict.getIssues(),
                        "comment", verdict.getComment()));
        log.info("自动核验驳回返工: subTaskId={}, reviewerAgentId={}, issues={}",
                subTaskId, reviewerAgentId, VerdictParser.summarize(verdict.getIssues(), 200));

        // 内循环闭合：对 API_KEY_LLM 执行者重新下发执行命令，触发返工重执行
        if (targetExecutor == null) {
            return;
        }
        Agent executor = agentService.getById(targetExecutor);
        if (executor == null || executor.getAccessType() != AgentAccessType.API_KEY_LLM) {
            log.debug("返工不自动重执行（执行者非 API_KEY_LLM 或不存在），等外部/人工链路: subTaskId={}, executorAgentId={}",
                    subTaskId, targetExecutor);
            return;
        }
        try {
            executionCommandService.createAssignedCommand(subTaskId, targetExecutor, "auto-review-rework");
            log.info("返工重执行命令已下发: subTaskId={}, executorAgentId={}", subTaskId, targetExecutor);
        } catch (Exception e) {
            log.warn("返工重执行命令下发失败（子任务停留 REWORK 等兜底）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
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
