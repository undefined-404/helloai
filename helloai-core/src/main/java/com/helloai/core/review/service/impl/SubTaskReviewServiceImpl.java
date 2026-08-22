package com.helloai.core.review.service.impl;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 子任务 LLM 自动核验服务实现（内循环核验门控）。
 *
 * <p>入口 {@link #reviewSubTask(Long, Long)}：读取子任务 title/content/deliverable/acceptance
 * + 执行产出（context.lastExecution.output），构造核验 Prompt，经
 * {@link PlatformAgentExecutionService#executeSync} 调平台内 LLM 判定：</p>
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
 */
@Slf4j
@Service
public class SubTaskReviewServiceImpl implements SubTaskReviewService {

    private static final String PROMPT_TEMPLATE_PATH = "prompts/subtask-review.md";

    /** §6.82 批次 D：核验互斥锁（防 L1/L2/L3 三路并发双审），key = review:lock:{subTaskId} */
    private static final String REVIEW_LOCK_PREFIX = "review:lock:";
    /** 锁 TTL 兜底：覆盖 LLM 调用超时窗口，崩溃残留自动过期 */
    private static final long REVIEW_LOCK_TTL_SECONDS = 120;

    private final SubTaskService subTaskService;
    private final AgentSelector agentSelector;
    private final AgentService agentService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ExecutionCommandService executionCommandService;
    private final AgentDispatchProperties dispatchProperties;
    private final ConversationService conversationService;
    private final ReviewService reviewService;
    private final TaskService taskService;
    private final StringRedisTemplate redis;
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;
    private final VerdictParser verdictParser;

    /**
     * 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在
     * IDE 增量编译里漏抓新增 final 字段的坑：显式列为 Spring DI 唯一依据）。
     */
    @Autowired
    public SubTaskReviewServiceImpl(SubTaskService subTaskService,
                                    AgentSelector agentSelector,
                                    AgentService agentService,
                                    PlatformAgentExecutionService platformAgentExecutionService,
                                    TaskTimelineService taskTimelineService,
                                    ExecutionCommandService executionCommandService,
                                    AgentDispatchProperties dispatchProperties,
                                    ConversationService conversationService,
                                    ReviewService reviewService,
                                    TaskService taskService,
                                    StringRedisTemplate redis,
                                    ReviewEvidenceAssembler reviewEvidenceAssembler,
                                    VerdictParser verdictParser) {
        this.subTaskService = subTaskService;
        this.agentSelector = agentSelector;
        this.agentService = agentService;
        this.platformAgentExecutionService = platformAgentExecutionService;
        this.taskTimelineService = taskTimelineService;
        this.executionCommandService = executionCommandService;
        this.dispatchProperties = dispatchProperties;
        this.conversationService = conversationService;
        this.reviewService = reviewService;
        this.taskService = taskService;
        this.redis = redis;
        this.reviewEvidenceAssembler = reviewEvidenceAssembler;
        this.verdictParser = verdictParser;
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

        Agent reviewer = pickReviewerAgent(subTask);
        if (reviewer == null) {
            log.warn("自动核验跳过：无可用平台内核验 Agent（REVIEWER/PLANNER 且 API_KEY_LLM），"
                    + "子任务停留 REVIEW 等人工: subTaskId={}", subTaskId);
            return;
        }

        String prompt = renderPrompt(subTask);
        AgentResult result;
        try {
            AgentTask agentTask = AgentTask.builder()
                    .subTaskId(subTaskId)
                    .systemPrompt("")
                    .userPrompt(prompt)
                    .context(Map.of("subTaskId", subTaskId, "scene", "subtask_review"))
                    .requiredCapabilities(Map.of())
                    .build();
            result = platformAgentExecutionService.executeSync(reviewer, agentTask);
        } catch (Exception e) {
            log.warn("自动核验 LLM 调用异常，子任务停留 REVIEW: subTaskId={}, err={}", subTaskId, e.getMessage());
            return;
        }
        if (result == null || !result.isSuccess()) {
            log.warn("自动核验 LLM 调用失败，子任务停留 REVIEW: subTaskId={}, err={}",
                    subTaskId, result != null ? result.getErrorMessage() : "null_result");
            return;
        }

        // 对话流双写：核验 Prompt + REVIEWER 分析原文全量落 conversation_message，
        // 不可解析时同样保留原始输出（正是人工兜底最需要看的内容）；失败不阻断核验主链路
        try {
            conversationService.addMessage(subTaskId, null,
                    "user", "platform", prompt, "subtask_review_prompt");
            // 推理模型的思考过程单独落一条消息（保留 thinking，供前端动态展示）
            if (result.getThinking() != null && !result.getThinking().isBlank()) {
                conversationService.addMessage(subTaskId, reviewer.getId(),
                        "assistant", "agent",
                        result.getThinking(),
                        "subtask_review_thinking");
            }
            conversationService.addMessage(subTaskId, reviewer.getId(),
                    "assistant", "agent",
                    result.getOutput() != null ? result.getOutput() : "",
                    "subtask_review_verdict");
        } catch (Exception e) {
            log.warn("核验对话流写入失败（不阻断核验）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        ReviewVerdict verdict = verdictParser.parseVerdict(result.getOutput());
        if (verdict == null) {
            log.warn("自动核验输出不可解析，子任务停留 REVIEW 等人工: subTaskId={}, rawOutput={}",
                    subTaskId, VerdictParser.summarize(result.getOutput(), 300));
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_unparseable", AgentRole.REVIEWER, reviewer.getId(),
                    Map.of("rawOutput", VerdictParser.summarize(result.getOutput(), 300)));
            return;
        }

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
     * 选平台内核验 Agent（§6.58 P1 指定语义）：
     * <ol>
     *   <li>任务级 {@code task.agent_policy.reviewerAgentId} 优先——指定 Agent
     *       可用（存在且 ACTIVE 且 API_KEY_LLM）时直接采用；失效时记录告警并回退；</li>
     *   <li>回退链：AgentSelector 优先 REVIEWER（API_KEY_LLM）→ 同角色 API_KEY_LLM
     *       → PLANNER 角色 API_KEY_LLM；均无则返回 null。</li>
     * </ol>
     */
    private Agent pickReviewerAgent(SubTask subTask) {
        // 任务级指定 reviewerAgentId 优先
        if (subTask != null && subTask.getTaskId() != null) {
            try {
                Task task = taskService.getById(subTask.getTaskId());
                Long policyReviewerId = TaskAgentPolicy.reviewerAgentId(
                        task != null ? task.getAgentPolicy() : null);
                if (policyReviewerId != null) {
                    Agent pinned = agentService.getById(policyReviewerId);
                    if (isUsableReviewer(pinned)) {
                        return pinned;
                    }
                    log.warn("指定的核验 Agent 不可用，回退自动选择: agentId={}, subTaskId={}",
                            policyReviewerId, subTask.getId());
                }
            } catch (Exception e) {
                log.debug("读取任务核验指定失败（按未指定处理）: taskId={}, err={}",
                        subTask.getTaskId(), e.getMessage());
            }
        }
        Agent preferred = agentSelector.pickPreferred(AgentRole.REVIEWER);
        if (preferred != null && preferred.getAccessType() == AgentAccessType.API_KEY_LLM) {
            return preferred;
        }
        Agent reviewer = firstApiKeyLlm(AgentRole.REVIEWER);
        if (reviewer != null) {
            return reviewer;
        }
        return firstApiKeyLlm(AgentRole.PLANNER);
    }

    /** 指定的核验 Agent 可用性校验（比创建时宽松失败：不抛错，回退自动）。 */
    private boolean isUsableReviewer(Agent agent) {
        return agent != null
                && agent.getStatus() == AgentStatus.ACTIVE
                && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

    private Agent firstApiKeyLlm(AgentRole role) {
        List<Agent> candidates = agentService.listByRole(role);
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .findFirst()
                .orElse(null);
    }

    /** 加载核验 Prompt 模板并替换占位符（证据/附件占位由装配器产出）。 */
    private String renderPrompt(SubTask subTask) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取核验 Prompt 模板失败: " + e.getMessage(), e);
        }
        return template
                .replace("{{SUB_TASK_TITLE}}", VerdictParser.nullToEmpty(subTask.getTitle()))
                .replace("{{SUB_TASK_CONTENT}}", VerdictParser.nullToEmpty(subTask.getContent()))
                .replace("{{DELIVERABLE}}", VerdictParser.nullToEmpty(subTask.getDeliverable()))
                .replace("{{ACCEPTANCE}}", VerdictParser.nullToEmpty(subTask.getAcceptance()))
                .replace("{{EXECUTION_OUTPUT}}", reviewEvidenceAssembler.extractExecutionOutput(subTask))
                .replace("{{ATTACHMENT_LIST}}", reviewEvidenceAssembler.buildAttachmentList(subTask))
                .replace("{{ATTACHMENT_CONTENT}}", reviewEvidenceAssembler.buildAttachmentContent(subTask))
                .replace("{{VERIFICATION_SIGNAL}}",
                        reviewEvidenceAssembler.verificationSignal(reviewEvidenceAssembler.extractRawOutput(subTask)));
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
