package com.helloai.core.review.service.impl;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateSeverity;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.quality.FinalReportFidelityGate;
import com.helloai.core.review.service.FinalReportReviewService;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.support.FinalReportFidelityChecker;
import com.helloai.core.review.support.FinalReportReviewFallbackWriter;
import com.helloai.core.review.support.FinalReportReviewLock;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.ReviewNotExecutedException;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.shared.event.TaskFinalReportGeneratedEvent;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.ExecutionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 最终整合报告质量审查实现（3A 核验/返工闭环 + §12.2 审查链三级容错）。
 *
 * <p><b>承接 {@link TaskFinalReportGeneratedEvent}（L1）</b>：报告写回事务<b>提交后</b>
 * 触发（{@link TransactionalEventListener} AFTER_COMMIT）→ 提交报告审查专用线程池执行：
 * 先跑确定性机械前置门（{@link FinalReportFidelityChecker}：空壳引用 / 围栏失衡命中即机械驳回，
 * 不给 LLM 放过的机会）→ 选 Reviewer（复用 {@link ReviewerPicker}，报告无子任务实体——传仅填
 * taskId 的探针，任务级 {@code reviewerAgentId} 同样生效）→ 渲染质量审查 Prompt（验收标准镜像
 * 3B 条款，输出 VerdictParser 可解析 JSON）→ 判定：</p>
 * <ul>
 *   <li><b>机械硬违规</b>：落 {@code task_final_report_review_rejected}（source=mechanical）并回调返工，
 *       不消耗审查 LLM 调用；</li>
 *   <li><b>机械软违规</b>（跨章逐字重复 / 覆盖追溯表缺失）：落 {@code task_final_report_review_warned}
 *       告警事件，交审查 LLM 重点关注（不直接驳回）；</li>
 *   <li><b>pass=true</b>：落 {@code task_final_report_review_passed}，闭环结束；</li>
 *   <li><b>pass=false 且未达返工上限</b>：落 rejected 事件并回调 {@link TaskFinalReportService#rework}
 *       （驳回意见注入重写 prompt 逐条响应）；</li>
 *   <li><b>pass=false 且已达上限</b>：落 {@code task_final_report_max_review_reached}，
 *       保留当前报告等人工/手动重生成；</li>
 *   <li><b>自审自过硬守卫</b>：reviewer 与写作者同 Agent 或选不到 reviewer 时，
 *       落 {@code task_final_report_review_skipped}（不得静默判 pass）。</li>
 * </ul>
 *
 * <p><b>§12.2 改造要点（L1/L2/L3 三路触发下的正确性）</b>：</p>
 * <ul>
 *   <li><b>L1 相位修正</b>：由普通 {@code @EventListener} 改为
 *       {@code @TransactionalEventListener(AFTER_COMMIT)}——发布点现在位于
 *       {@code FinalReportPersistService} 的事务内，若仍用普通监听器会在<b>提交前</b>触发，
 *       审查读到未提交的旧 {@code final_report_time} 会命中陈旧守卫被丢弃（静默卡死）。</li>
 *   <li><b>防双审互斥锁</b>：L1/L2/L3 可能并发触发同一任务审查，Redis 锁
 *       （{@code final_report_review:{taskId}}）保证 LLM 调用窗口内仅一路进入，
 *       与子任务核验链（§6.82）同款。</li>
 *   <li><b>状态守卫</b>：进入审查体后若 {@code final_report_status != REVIEWING}，
 *       说明已被兄弟链路收敛 → 幂等跳过，不再选人、不再调 LLM。</li>
 *   <li><b>收敛出口统一</b>：所有出口的 DONE 写回都走
 *       {@link TaskService#convergeFinalReportToDone}（带 {@code final_report_status} +
 *       {@code final_report_time} 双条件 CAS），杜绝旧链覆盖新链状态。</li>
 * </ul>
 *
 * <p>审查失败/异常仅影响质量闭环，不影响任务 DONE 与报告已落库事实（与报告生成同哲学）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalReportReviewServiceImpl implements FinalReportReviewService {

    private static final String REVIEW_TEMPLATE_PATH = "prompts/task-final-report-review.md";
    /** 审查证据注入的子任务数量上限（防上下文爆炸；超出仅注入前 N 个，其余以清单为准）。 */
    private static final int MAX_EVIDENCE_SUB_TASKS = 10;
    /**
     * 防双审互斥锁键前缀 + 租期：统一收敛到 {@link FinalReportReviewLock}——L1/L2 审查体与
     * L3 孤儿巡检（{@code FinalReportReviewOrphanTask}）共用同一把锁，杜绝两处各写一份导致漂移。
     */
    private static final String REVIEW_LOCK_PREFIX = FinalReportReviewLock.KEY_PREFIX;
    private static final long REVIEW_LOCK_TTL_SECONDS = FinalReportReviewLock.TTL_SECONDS;

    private final TaskFinalReportService taskFinalReportService;
    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final TaskRunningSpecService taskRunningSpecService;
    private final ReviewerPicker reviewerPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final VerdictParser verdictParser;
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties dispatchProperties;
    private final RedissonClient redissonClient;
    /** §12.2 审查专用池（helloai-start {@code ReportReviewExecutorConfig}）：审查提交后发布线程立即返回。 */
    @Qualifier("reportReviewExecutor")
    private final Executor reportReviewExecutor;
    /** §12.5.5 #4：AFTER_COMMIT / 池满兜底落库经 REQUIRES_NEW 独立事务，避免加入已提交事务而丢失。 */
    private final FinalReportReviewFallbackWriter fallbackWriter;
    /** RM12（B4 Quality Gate 泛化）：最终报告保真机械闸门（持有纯静态 {@link FinalReportFidelityChecker}）。 */
    private final FinalReportFidelityGate fidelityGate;

    /**
     * L1 入口：AFTER_COMMIT 只做<b>提交</b>不阻塞发布线程。<p>
     *
     * <p>§12.2 审查异步化：审查体（选人 + LLM 判定 + 返工）提交到专用池执行。不用 {@code @Async}——
     * 其拒绝异常走 {@code AsyncUncaughtExceptionHandler} 不会回到发布线程，无法落
     * {@code review_skipped(executor_saturated)}；手动 {@code execute} + try-catch
     * {@link RejectedExecutionException} 才能在发布线程兜底。</p>
     *
     * <p>§12.5.5 #4：本方法运行在报告写回事务<b>已提交</b>后的发布线程上，两处同步兜底写入
     * （开关关闭收敛 / 池满 {@code review_skipped}+收敛）经 {@link FinalReportReviewFallbackWriter}
     * 的 {@code REQUIRES_NEW} 独立事务落库，避免加入已提交事务而被静默丢弃。</p>
     */
    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinalReportGenerated(TaskFinalReportGeneratedEvent event) {
        if (!dispatchProperties.isAutoFinalReportReviewEnabled()) {
            log.debug("最终报告自动审查未启用，跳过: taskId={}, attempt={}", event.getTaskId(), event.getAttempt());
            // 收敛兜底：开关若在生成后、审查前被关闭，把可能残留的 REVIEWING 收敛 DONE（条件写回，版本未变才生效）
            convergeToDone(event);
            return;
        }
        try {
            reportReviewExecutor.execute(() -> reviewQuietly(event));
        } catch (RejectedExecutionException e) {
            log.warn("最终报告审查线程池已满，本次审查跳过: taskId={}, attempt={}", event.getTaskId(), event.getAttempt());
            skipped(event, "executor_saturated");
        }
    }

    @Override
    public void review(Long taskId, OffsetDateTime reportTime, int attempt, int reportLength) {
        // sectionCount 仅为事件度量，审查 Prompt 不用它（用 reportLength），L2 路径传 0 即可
        reviewInternal(new TaskFinalReportGeneratedEvent(taskId, reportLength, 0, attempt, reportTime));
    }

    /** 异步审查体：异常吞掉（审查失败/异常不影响报告交付，与报告生成同哲学）。 */
    private void reviewQuietly(TaskFinalReportGeneratedEvent event) {
        try {
            reviewInternal(event);
        } catch (ReviewNotExecutedException e) {
            // §12.5.5 #5：L1 语义——另一路审查在途或锁不可用属正常跳过（L1 尽力而为，
            // 重投/兜底由 L2 死信重放与 L3 孤儿巡检负责），记 debug 不外抛、不影响发布线程。
            log.debug("最终报告审查跳过（L1，另一路在途或锁不可用）: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("最终报告审查异常（不影响报告交付）: taskId={}, err={}", event.getTaskId(), e.getMessage());
        }
    }

    /**
     * 审查体（L1/L2 共用）：Redis 防双审锁 → 陈旧守卫 → 状态守卫 → 机械门 → LLM 判定 → 处置。
     */
    private void reviewInternal(TaskFinalReportGeneratedEvent event) {
        RLock lock = redissonClient.getLock(REVIEW_LOCK_PREFIX + event.getTaskId());
        boolean locked;
        try {
            // waitTime=0 保持"抢占失败即跳过"语义；显式 leaseTime 禁看门狗，TTL 兜底崩溃残留
            locked = lock.tryLock(0, REVIEW_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // §12.5.5 #5：被中断 = 未真正执行审查 → 抛出（L2 判失败重投；L1 由 reviewQuietly 静默跳过）
            throw new ReviewNotExecutedException("最终报告审查获取锁被中断: taskId=" + event.getTaskId(), e);
        } catch (Exception e) {
            // §12.5.5 #5：锁不可用（Redis 异常等）同属"未执行"，抛出交调用方重投，不误判成功
            throw new ReviewNotExecutedException(
                    "最终报告审查获取锁异常，本次未执行: taskId=" + event.getTaskId() + ", err=" + e.getMessage(), e);
        }
        if (!locked) {
            // §12.5.5 #5：抢锁失败 = 另一路审查在途 = 本次未执行任何审查逻辑。
            // 不再静默 return（那会让 L2 误判消费成功并永久 ACK，L1 崩溃后无链路重投）——
            // 抛出：L1 静默跳过，L2 走 markFailed + basicNack(requeue=false) 进死信台账可重放。
            throw new ReviewNotExecutedException(
                    "最终报告审查跳过：已有审查进行中（防双审）: taskId=" + event.getTaskId()
                            + ", attempt=" + event.getAttempt());
        }
        try {
            doReview(event);
        } catch (Exception e) {
            // 审查已开始执行后的异常遵循"审查失败不影响报告交付"哲学就地吞掉（不外抛，
            // 避免 L2 重投重复烧 LLM）；仅"锁都没抢到、一行审查都没跑"才抛 ReviewNotExecutedException。
            log.warn("最终报告审查执行异常（不影响报告交付）: taskId={}, err={}", event.getTaskId(), e.getMessage());
        } finally {
            // isHeldByCurrentThread 防御：锁已过期被他人接管时，本线程不得释放他人持有的锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * §12.2 REVIEWING 收敛：条件写回 DONE（{@code final_report_status=REVIEWING} 且
     * {@code final_report_time} 未被替换才生效）。影响行数 0 = 版本已被新生成/回滚接管
     * 或已被兄弟链路收敛，本链放弃收敛，不覆盖新链状态。
     */
    private void convergeToDone(TaskFinalReportGeneratedEvent event) {
        // §12.5.5 #4：经 REQUIRES_NEW 独立事务落库——AFTER_COMMIT 发布线程上直接写会加入
        // 已提交事务而丢失，下沉到 FinalReportReviewFallbackWriter 确保兜底收敛确定提交。
        fallbackWriter.convergeToDone(event.getTaskId(), event.getReportTime());
    }

    /**
     * §12.2 陈旧守卫：库中 {@code final_report_time} 与事件锚点不一致 = 版本已被更新/回滚，旧链失效。
     *
     * <p><b>必须按「同一瞬间」比较</b>：pgjdbc 读 {@code timestamptz} 返回 UTC 偏移
     * （{@code ...Z}），而事件锚点是写入时的本地偏移（{@code ...+08:00}）——两者是同一瞬间。
     * {@link OffsetDateTime#equals(Object)} 连偏移一起比，会**恒不相等**，把每次生成都误判为陈旧
     * （实测：审查被丢弃且不收敛 → 报告永久卡在 {@code REVIEWING}，需人工介入）；
     * 故用 {@link OffsetDateTime#isEqual(OffsetDateTime)}（只比瞬间，与时区无关）。</p>
     */
    private static boolean isStale(TaskView task, TaskFinalReportGeneratedEvent event) {
        OffsetDateTime dbTime = task.finalReportTime();
        OffsetDateTime eventTime = event.getReportTime();
        if (dbTime == null || eventTime == null) {
            return true;
        }
        return !dbTime.isEqual(eventTime);
    }

    private void doReview(TaskFinalReportGeneratedEvent event) {
        TaskView task = taskService.getView(event.getTaskId());
        if (task == null || task.finalReport() == null || task.finalReport().isBlank()) {
            log.debug("报告不存在或为空，跳过审查: taskId={}", event.getTaskId());
            return;
        }
        // §12.2 状态守卫：已被兄弟链路（L1/L2/L3）收敛或审查开关关闭时写回的 DONE → 幂等跳过。
        // 这是三路触发不重复审查的关键守卫（Redis 锁只挡并发窗口，退出后到达的路径靠本守卫挡住）。
        if (task.finalReportStatus() != FinalReportStatus.REVIEWING) {
            log.debug("最终报告审查跳过：状态已非 REVIEWING（已被收敛/接管）: taskId={}, status={}",
                    event.getTaskId(), task.finalReportStatus());
            return;
        }
        // §12.2 陈旧守卫（审查前）：期间被重新生成/回滚接管 → 旧链审查丢弃，不选人不动用 LLM
        if (isStale(task, event)) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_discarded_stale", AgentRole.REVIEWER, null,
                    Map.of("attempt", event.getAttempt()));
            log.debug("最终报告审查丢弃（版本已更新，旧链失效）: taskId={}, attempt={}",
                    event.getTaskId(), event.getAttempt());
            return;
        }
        // 3A-V2 机械前置门（确定性、零 token）：空壳引用 / 围栏失衡命中即机械驳回，
        // 不给 LLM 放过的机会（实测「完整矩阵见分册」曾被 LLM 判 pass）
        GateDecision fidelity = fidelityGate.evaluate(task.finalReport());
        if (fidelity.blocks()) {
            log.info("最终报告机械校验命中硬违规，直接驳回: taskId={}, issues={}",
                    event.getTaskId(), fidelity.detailList(GateSeverity.HARD));
            rejectOrRework(event, null, 0, fidelity.detailList(GateSeverity.HARD), "mechanical");
            return;
        }
        AgentProfileSnapshot reviewer = pickReviewer(event.getTaskId());
        if (reviewer == null) {
            skipped(event, "no_reviewer_available");
            return;
        }
        // 自审自过硬守卫：审查人不得是报告写作者（不满足则跳过审查，不得静默判 pass）
        if (task.finalReportAgentId() != null
                && reviewer.id().equals(task.finalReportAgentId())) {
            skipped(event, "self_review_guard");
            return;
        }
        // 机械软违规（跨章逐字重复 / 覆盖追溯表缺失）：落告警事件，交审查 LLM 重点关注（不直接驳回）
        if (fidelity.hasSeverity(GateSeverity.SOFT)) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_warned", AgentRole.REVIEWER, reviewer.id(),
                    Map.of("softIssues", fidelity.detailList(GateSeverity.SOFT), "attempt", event.getAttempt()));
            log.info("最终报告机械校验命中软违规（交 LLM 复核）: taskId={}, soft={}",
                    event.getTaskId(), fidelity.detailList(GateSeverity.SOFT));
        }
        AgentResult result = callReviewLlm(event, task, reviewer);
        if (result == null) {
            // §12.2 REVIEWING 收敛：审查 LLM 调用失败/异常仅记 failed 事件（callReviewLlm 内部），
            // 报告照常交付——不收敛则 REVIEWING 永远卡住无恢复口
            convergeToDone(event);
            return;
        }
        SubTaskReviewService.ReviewVerdict verdict = verdictParser.parseVerdict(result.getOutput());
        if (verdict == null) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_unparseable", AgentRole.REVIEWER, reviewer.id(),
                    Map.of("rawOutput", VerdictParser.summarize(result.getOutput(), 300),
                            "attempt", event.getAttempt()));
            log.warn("最终报告审查输出不可解析: taskId={}, raw={}", event.getTaskId(),
                    VerdictParser.summarize(result.getOutput(), 300));
            // §12.2 REVIEWING 收敛：审查无法判定仅记事件，报告照常交付
            convergeToDone(event);
            return;
        }
        int score = verdict.getScore() != null ? verdict.getScore() : 0;
        if (Boolean.TRUE.equals(verdict.getPass())) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_passed", AgentRole.REVIEWER, reviewer.id(),
                    Map.of("reviewerAgentId", reviewer.id(), "score", score,
                            "attempt", event.getAttempt()));
            log.info("最终报告审查通过: taskId={}, reviewerAgentId={}, score={}",
                    event.getTaskId(), reviewer.id(), score);
            // §12.2 REVIEWING 收敛：审查通过置 DONE（条件写回，版本未变才生效）
            convergeToDone(event);
            return;
        }
        rejectOrRework(event, reviewer.id(), score,
                verdict.getIssues() != null ? verdict.getIssues() : "（无具体驳回意见）", "llm");
    }

    /**
     * 驳回统一处置：落 {@code task_final_report_review_rejected} 事件 → 未达返工上限回调
     * {@link TaskFinalReportService#rework}；达上限落 {@code task_final_report_max_review_reached}
     * 保留当前报告等人工（不重写）。
     *
     * @param reviewerAgentId 审查人（机械驳回时为 null）
     * @param source          驳回来源：{@code mechanical}（确定性校验）/ {@code llm}（审查模型）
     */
    private void rejectOrRework(TaskFinalReportGeneratedEvent event, Long reviewerAgentId,
                                int score, String issues, String source) {
        int maxReview = dispatchProperties.getAutoFinalReportMaxReview();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewerAgentId", reviewerAgentId);
        payload.put("score", score);
        payload.put("attempt", event.getAttempt());
        payload.put("issues", issues != null ? issues : "");
        payload.put("source", source);
        taskTimelineService.recordEvent(event.getTaskId(), null,
                "task_final_report_review_rejected", AgentRole.REVIEWER, reviewerAgentId, payload);
        if (maxReview > 0 && event.getAttempt() <= maxReview) {
            // §12.2 陈旧守卫（rework 前）：审查耗时期间版本可能已被新生成/回滚接管——
            // 此时返工基于旧链已无意义，丢弃并落 rework_discarded_stale（不重写不收敛）
            TaskView latest = taskService.getView(event.getTaskId());
            if (latest == null || isStale(latest, event)) {
                taskTimelineService.recordEvent(event.getTaskId(), null,
                        "task_final_report_rework_discarded_stale", AgentRole.REVIEWER,
                        reviewerAgentId, Map.of("attempt", event.getAttempt()));
                log.debug("最终报告返工丢弃（版本已更新，旧链失效）: taskId={}, attempt={}",
                        event.getTaskId(), event.getAttempt());
                return;
            }
            log.info("最终报告审查驳回（{}），触发返工重写: taskId={}, attempt={}, maxReview={}",
                    source, event.getTaskId(), event.getAttempt(), maxReview);
            // 去状态化（§12.3）：同轮返工显式传 attempt + 1，轮次只随调用链传递、无任何存储
            taskFinalReportService.rework(event.getTaskId(),
                    issues != null ? issues : "（无具体驳回意见）", event.getAttempt() + 1);
            return;
        }
        taskTimelineService.recordEvent(event.getTaskId(), null,
                "task_final_report_max_review_reached", AgentRole.REVIEWER, reviewerAgentId,
                Map.of("attempt", event.getAttempt(), "maxReview", maxReview));
        log.info("最终报告驳回已达返工上限，保留当前报告等人工: taskId={}, attempt={}, maxReview={}",
                event.getTaskId(), event.getAttempt(), maxReview);
        // §12.2 REVIEWING 收敛：达上限不再重写，报告保留现状并收敛 DONE（不再等待）
        convergeToDone(event);
    }

    /** 渲染审查 Prompt 并调用平台 LLM；调用失败/异常返回 null（内部已记 failed 事件）。 */
    private AgentResult callReviewLlm(TaskFinalReportGeneratedEvent event, TaskView task, AgentProfileSnapshot reviewer) {
        String prompt;
        try {
            prompt = renderReviewPrompt(task, event);
        } catch (Exception e) {
            log.warn("最终报告审查 Prompt 渲染失败: taskId={}, err={}", event.getTaskId(), e.getMessage());
            return null;
        }
        try {
            AgentTask agentTask = AgentTask.builder()
                    .systemPrompt("")
                    .userPrompt(prompt)
                    .context(Map.of("taskId", event.getTaskId(), "scene", "task_final_report_review"))
                    .requiredCapabilities(Map.of())
                    .build();
            AgentResult result = platformAgentExecutionService.executeSync(reviewer.id(), agentTask);
            if (result == null || !result.isSuccess()) {
                taskTimelineService.recordEvent(event.getTaskId(), null,
                        "task_final_report_review_failed", AgentRole.REVIEWER, reviewer.id(),
                        Map.of("error", result != null ? result.getErrorMessage() : "null_result",
                                "attempt", event.getAttempt()));
                log.warn("最终报告审查 LLM 调用失败: taskId={}, err={}",
                        event.getTaskId(), result != null ? result.getErrorMessage() : "null_result");
                return null;
            }
            return result;
        } catch (Exception e) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_failed", AgentRole.REVIEWER, reviewer.id(),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                            "attempt", event.getAttempt()));
            log.warn("最终报告审查 LLM 调用异常: taskId={}, err={}", event.getTaskId(), e.getMessage());
            return null;
        }
    }

    /** 报告无子任务实体：传探针（仅填 taskId）使任务级 {@code reviewerAgentId} 生效（SubTaskView 仅 @Data 无 @Builder）。 */
    private AgentProfileSnapshot pickReviewer(Long taskId) {
        return reviewerPicker.pickSingle(SubTaskView.taskScopedProbe(taskId));
    }

    /** 跳过审查落库标记（reason 区分守卫/无可用 reviewer/池满），不做任何 pass 判定；审查跳过即收敛 DONE。 */
    private void skipped(TaskFinalReportGeneratedEvent event, String reason) {
        log.info("最终报告审查跳过（{}）: taskId={}, attempt={}", reason, event.getTaskId(), event.getAttempt());
        // §12.5.5 #4：review_skipped 审计 + REVIEWING 收敛下沉到 REQUIRES_NEW 独立事务，两步原子提交——
        // AFTER_COMMIT 池满兜底路径上不再因加入已提交事务而双双丢失。
        fallbackWriter.recordSkippedAndConverge(event.getTaskId(), event.getReportTime(),
                event.getAttempt(), reason);
    }

    /** 渲染审查 Prompt：任务信息 + 报告正文 + 子任务产出证据（产出摘要复用 review 域装配器同款口径）。 */
    private String renderReviewPrompt(TaskView task, TaskFinalReportGeneratedEvent event) {
        ClassPathResource resource = new ClassPathResource(REVIEW_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("未找到最终报告审查 Prompt 模板: " + REVIEW_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取最终报告审查 Prompt 模板失败: " + e.getMessage(), e);
        }
        return template
                .replace("{{TASK_TITLE}}", task.title() != null ? task.title() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.description() != null && !task.description().isBlank()
                                ? task.description() : "（无补充描述）")
                .replace("{{FINAL_REPORT}}", task.finalReport() != null ? task.finalReport() : "")
                .replace("{{SUB_TASK_EVIDENCE}}", buildSubTaskEvidence(event.getTaskId()))
                .replace("{{ATTEMPT}}", String.valueOf(event.getAttempt()))
                .replace("{{REPORT_LENGTH}}", String.valueOf(event.getReportLength()));
    }

    /** 子任务产出证据：编号/标题/交付物/验收/执行摘要 + 产出摘要（与报告链同口径的只读事实源）。 */
    private String buildSubTaskEvidence(Long taskId) {
        List<SubTaskView> done = subTaskService.listViewsByTaskId(taskId).stream()
                .filter(st -> st.status() == SubTaskStatus.DONE)
                .toList();
        if (done.isEmpty()) {
            return "（无 DONE 子任务产出证据）";
        }
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < done.size(); i++) {
            if (i >= MAX_EVIDENCE_SUB_TASKS) {
                truncated = true;
                break;
            }
            SubTaskView st = done.get(i);
            sb.append("### #").append(i + 1).append(' ')
                    .append(st.title() != null ? st.title() : "（无标题）").append('\n');
            if (st.deliverable() != null && !st.deliverable().isBlank()) {
                sb.append("- 交付物要求：").append(st.deliverable()).append('\n');
            }
            if (st.acceptance() != null && !st.acceptance().isBlank()) {
                sb.append("- 验收标准：").append(st.acceptance()).append('\n');
            }
            ExecutionRecord record = taskRunningSpecService.findRecord(taskId, st.id());
            if (record != null && record.summary() != null && !record.summary().isBlank()) {
                sb.append("- 执行摘要：").append(record.summary()).append('\n');
            }
            sb.append("- 产出摘要：").append(reviewEvidenceAssembler.extractExecutionOutput(st)).append('\n');
        }
        if (truncated) {
            sb.append("\n（产出证据数量超上限，其余子任务仅以上述清单为准）\n");
        }
        return sb.toString().trim();
    }
}
