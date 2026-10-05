package com.helloai.core.review.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.quality.FinalReportFidelityGate;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.support.FinalReportReviewFallbackWriter;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FinalReportReviewServiceImpl 单元测试：最终报告质量审查闭环（3A）。
 *
 * <p>覆盖：开关关闭跳过、报告缺失跳过、无可用 reviewer 跳过、自审自过硬守卫
 * （review_skipped 而非静默 pass）、解析不可用落 unparseable、pass=true 落 passed、
 * 驳回在返工上限内回调 rework、达上限落 max_review_reached 不再重写、
 * 审查 Prompt 注入报告正文与子任务产出证据（与报告链同口径）、
 * §12.2 状态守卫（非 REVIEWING 幂等跳过）与 Redis 防双审锁。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinalReportReviewServiceImpl 最终报告质量审查闭环")
class FinalReportReviewServiceImplTest {

    private static final Long TASK_ID = 1L;
    /** 报告写作者（Planner）AgentId。 */
    private static final Long WRITER_AGENT_ID = 11L;
    /** 审查人（Reviewer）AgentId。 */
    private static final Long REVIEWER_AGENT_ID = 22L;
    /** §12.2 陈旧守卫锚点：事件 reportTime 与库中 final_report_time 的基准值（双方一致=版本未变）。 */
    private static final OffsetDateTime REPORT_TIME = OffsetDateTime.parse("2026-09-28T10:00:00+08:00");

    @Mock
    private TaskFinalReportService taskFinalReportService;
    @Mock
    private TaskService taskService;
    @Mock
    private SubTaskService subTaskService;
    @Mock
    private TaskRunningSpecService taskRunningSpecService;
    @Mock
    private ReviewerPicker reviewerPicker;
    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;
    @Mock
    private VerdictParser verdictParser;
    @Mock
    private ReviewEvidenceAssembler reviewEvidenceAssembler;
    @Mock
    private TaskTimelineService taskTimelineService;
    /** §12.2 防双审互斥锁（L1/L2/L3 三路触发并发时仅一路进入审查体）。 */
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock reviewLock;
    /** §12.2 审查专用池（mock；默认同步执行以保持单测断言语义，池满/提交用例内单独覆盖）。 */
    @Mock
    private Executor reportReviewExecutor;

    private final AgentDispatchProperties dispatchProperties = new AgentDispatchProperties();

    @SuppressWarnings("unchecked")

    private FinalReportReviewServiceImpl reviewService;
    private AgentProfileSnapshot reviewer;

    /**
     * §12.2 收敛写回经 {@code taskService.convergeFinalReportToDone}（mock），本类不再自建
     * {@code LambdaUpdateWrapper}；保留 TableInfo 初始化以兼容其它直接构造 wrapper 的断言路径。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), TaskView.class);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        reviewService = new FinalReportReviewServiceImpl(taskFinalReportService, taskService, subTaskService,
                taskRunningSpecService, reviewerPicker, platformAgentExecutionService,
                verdictParser, reviewEvidenceAssembler, taskTimelineService, dispatchProperties,
                redissonClient, reportReviewExecutor, fallbackWriter(), new FinalReportFidelityGate());
        // §12.2 异步化：默认同步执行 Runnable（单测断言仍按顺序生效）；池满用例内改为抛拒绝异常
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(reportReviewExecutor).execute(any());
        // §12.2 防双审锁：默认占锁成功（并发占锁失败用例内单独覆盖）
        when(redissonClient.getLock(anyString())).thenReturn(reviewLock);
        when(reviewLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(reviewLock.isHeldByCurrentThread()).thenReturn(true);

        lenient().when(subTaskService.listViewsByTaskId(anyLong())).thenReturn(List.of());
        when(reviewEvidenceAssembler.extractExecutionOutput(any())).thenReturn("（产出概览：物化附件摘要）");
        // §12.2 REVIEWING 收敛写回：默认成功（收敛失败用例内单独覆盖为 false）
        when(taskService.convergeFinalReportToDone(any(), any())).thenReturn(true);

        reviewer = AgentProfileSnapshot.builder()
                .id(REVIEWER_AGENT_ID)
                .role(AgentRole.REVIEWER)
                .build();
    }

    // ---------- 跳过路径 ----------

    @Test
    @DisplayName("开关关闭：不触发任何审查行为，但收敛兜底把残留 REVIEWING 落 DONE")
    void shouldSkipReviewWhenDisabled() {
        dispatchProperties.setAutoFinalReportReviewEnabled(false);

        reviewService.onFinalReportGenerated(event(1));

        verify(taskService, never()).getView(any());
        verify(reviewerPicker, never()).pickSingle(any());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
        // §12.2 收敛兜底：开关关闭时条件写回 DONE（版本未变才生效）
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
    }

    @Test
    @DisplayName("报告未落库：跳过审查且不选人")
    void shouldSkipWhenReportNotPersisted() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, null));

        reviewService.onFinalReportGenerated(event(1));

        verify(reviewerPicker, never()).pickSingle(any());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
    }

    @Test
    @DisplayName("选不到 Reviewer：落 review_skipped(no_reviewer_available) 而非静默 pass")
    void shouldSkipWhenNoReviewerAvailable() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(null);

        reviewService.onFinalReportGenerated(event(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_skipped"), eq(AgentRole.REVIEWER), isNull(),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("reason", "no_reviewer_available");
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
        // §12.2 REVIEWING 收敛：审查跳过即收敛 DONE
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
    }

    @Test
    @DisplayName("自审自过硬守卫：reviewer 与写作者同 Agent 时落 review_skipped(self_review_guard) 不调用 LLM")
    void shouldSkipOnSelfReviewGuard() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        AgentProfileSnapshot writerReviewer = AgentProfileSnapshot.builder()
                .id(WRITER_AGENT_ID)
                .role(AgentRole.REVIEWER)
                .build();
        when(reviewerPicker.pickSingle(any())).thenReturn(writerReviewer);

        reviewService.onFinalReportGenerated(event(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_skipped"), eq(AgentRole.REVIEWER), isNull(),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("reason", "self_review_guard");
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
        // §12.2 REVIEWING 收敛：审查跳过即收敛 DONE
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
    }

    // ---------- §12.2 三路触发幂等（状态守卫 + 防双审锁） ----------

    @Test
    @DisplayName("§12.2 状态守卫：状态已非 REVIEWING（被兄弟链路收敛）→ 幂等跳过，不选人不再收敛")
    void shouldSkipWhenStatusAlreadyConverged() {
        TaskView converged = taskView(TASK_ID, "# 整合报告", "调度分析", "梳理调度链路",
                FinalReportStatus.DONE, REPORT_TIME);
        when(taskService.getView(TASK_ID)).thenReturn(converged);

        reviewService.onFinalReportGenerated(event(1));

        verify(reviewerPicker, never()).pickSingle(any());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
        verify(taskService, never()).convergeFinalReportToDone(any(), any());
    }

    @Test
    @DisplayName("§12.5.5 #5 L1 路径：抢占失败（已有审查在跑）→ reviewQuietly 静默跳过，不外抛、不读任务不调 LLM")
    void shouldSkipWhenReviewLockNotAcquired() throws InterruptedException {
        when(reviewLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        // L1：AFTER_COMMIT → 专用池 → reviewQuietly；抢锁失败属正常跳过，异常被 reviewQuietly 吞掉不外抛
        reviewService.onFinalReportGenerated(event(1));

        verify(taskService, never()).getView(any());
        verify(reviewerPicker, never()).pickSingle(any());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
    }

    @Test
    @DisplayName("§12.5.5 #5 L2 路径：抢占失败 → review 抛 ReviewNotExecutedException（供 MQ 消费者 markFailed+NACK 重投）")
    void shouldThrowWhenL2ReviewCannotAcquireLock() throws InterruptedException {
        when(reviewLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        // L2：MQ 消费者经 tryConsume 调用 review(...)；未真正执行必须抛出，才能触发
        // markFailed + basicNack(requeue=false) 进死信台账，而非静默 ACK 把 eventId 永久标记已消费。
        assertThatThrownBy(() -> reviewService.review(TASK_ID, REPORT_TIME, 1, 128))
                .isInstanceOf(ReviewNotExecutedException.class);

        // 抢锁失败即短路：一行审查逻辑都没跑
        verify(taskService, never()).getView(any());
        verify(reviewerPicker, never()).pickSingle(any());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
    }

    @Test
    @DisplayName("§12.5.5 #5 边界：已抢到锁后 doReview 抛异常 → review 不外抛（已执行，吞掉不重投避免重复烧 LLM）")
    void shouldSwallowExceptionAfterLockAcquired() {
        when(taskService.getView(TASK_ID)).thenThrow(new RuntimeException("db down"));

        // 已进审查体（抢到锁）后的异常属"已执行但失败"，就地吞掉：L2 视为已消费 ACK，不重投
        reviewService.review(TASK_ID, REPORT_TIME, 1, 128);

        verify(taskService).getView(TASK_ID);
        verify(reviewerPicker, never()).pickSingle(any());
    }

    // ---------- §12.2 审查异步化与陈旧守卫 ----------

    @Test
    @DisplayName("§12.2 异步化：审查经专用池提交执行（发布线程不直接跑审查体）")
    void shouldSubmitReviewToDedicatedExecutor() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":4}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());

        // 覆盖 setUp 同步执行 stub：本次只提交不执行，手动 run 验证「提交与执行分离」
        doNothing().when(reportReviewExecutor).execute(any());
        reviewService.onFinalReportGenerated(event(1));

        // 审查体整体作为 Runnable 提交到专用池；发布线程内审查体尚未执行（未选人）
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(reportReviewExecutor).execute(runnableCaptor.capture());
        verify(reviewerPicker, never()).pickSingle(any());
        // 池子执行后审查体才运行
        runnableCaptor.getValue().run();
        verify(reviewerPicker).pickSingle(any());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_passed"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
    }

    @Test
    @DisplayName("§12.2 池满拒绝：落 review_skipped(executor_saturated) 并收敛 DONE，不选人")
    void shouldSkipAndConvergeWhenExecutorSaturated() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        doThrow(new RejectedExecutionException("queue full"))
                .when(reportReviewExecutor).execute(any());

        reviewService.onFinalReportGenerated(event(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_skipped"), eq(AgentRole.REVIEWER), isNull(),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("reason", "executor_saturated");
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
        verify(reviewerPicker, never()).pickSingle(any());
    }

    @Test
    @DisplayName("§12.2 陈旧守卫：库值经 JDBC 回读为 UTC 偏移（同一瞬间）→ 不得误判陈旧（回归）")
    void shouldNotTreatSameInstantDifferentOffsetAsStale() {
        // 复现生产口径：pgjdbc 读 timestamptz 返回 UTC 偏移，事件锚点为写入时的 +08:00——同一瞬间
        TaskView sameInstantUtc = taskView(TASK_ID, "# 整合报告", "调度分析", "梳理调度链路",
                FinalReportStatus.REVIEWING, REPORT_TIME.withOffsetSameInstant(ZoneOffset.UTC));
        when(taskService.getView(TASK_ID)).thenReturn(sameInstantUtc);
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":4}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        // 不得误判陈旧：不落 discarded_stale、不跳过审查
        verify(taskTimelineService, never()).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_discarded_stale"), any(), any(), anyMap());
        verify(platformAgentExecutionService).executeSync(anyLong(), any(AgentTask.class));
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_passed"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
    }

    @Test
    @DisplayName("§12.2 陈旧守卫（审查前）：reportTime 不匹配 → 丢弃审查不选人不调 LLM")
    void shouldDiscardStaleReview() {
        // 版本已被新生成/回滚接管
        TaskView stale = taskView(TASK_ID, "# 整合报告", "调度分析", "梳理调度链路",
                FinalReportStatus.REVIEWING, REPORT_TIME.plusSeconds(30));
        when(taskService.getView(TASK_ID)).thenReturn(stale);

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_discarded_stale"), eq(AgentRole.REVIEWER), isNull(),
                anyMap());
        verify(reviewerPicker, never()).pickSingle(any());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        // 旧链丢弃：不收敛新链状态（新链接管，收敛由新链负责）
        verify(taskService, never()).convergeFinalReportToDone(any(), any());
    }

    @Test
    @DisplayName("§12.2 陈旧守卫（rework 前）：审查期间版本被接管 → 返工丢弃落 rework_discarded_stale")
    void shouldDiscardStaleRework() {
        TaskView reviewed = task(TASK_ID, "# 整合报告");
        TaskView latest = taskView(TASK_ID, "# 整合报告（新生成接管）", "调度分析", "梳理调度链路",
                FinalReportStatus.REVIEWING, REPORT_TIME.plusSeconds(30));
        when(taskService.getView(TASK_ID)).thenReturn(reviewed, latest);
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":false,\"score\":2}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(rejectedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_rejected"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_rework_discarded_stale"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
    }

    @Test
    @DisplayName("§12.2 收敛写回携带事件锚点：pass 后以 `final_report_time = 事件 reportTime` 为条件收敛 DONE")
    void shouldConvergeWithReportTimeGuard() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":4}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        // §12.5 收敛写口收口到 TaskService.convergeFinalReportToDone：状态 + 时间双条件 CAS
        // 具体 SQL 条件由 TaskServiceImpl 单测覆盖，此处断言锚点透传正确
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
    }

    @Test
    @DisplayName("§12.2 收敛竞争：版本已被接管（CAS 0 行）→ 不抛异常，新链状态不被覆盖")
    void shouldNotOverwriteWhenConvergenceCompetes() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":4}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());
        when(taskService.convergeFinalReportToDone(any(), any())).thenReturn(false); // CAS 0 行（版本已变）

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_passed"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        // 收敛失败静默放弃：报告已交付事实不变，状态归属新链
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
    }

    // 下面用例中涉及的收敛断言（pass/驳回达上限等出口）
    @Test
    @DisplayName("§12.2 收敛全覆盖：LLM 输出不可解析出口同样收敛 DONE")
    void shouldConvergeOnUnparseable() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("乱七八糟", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(null);

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_unparseable"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskService).convergeFinalReportToDone(eq(TASK_ID), eq(REPORT_TIME));
    }

    @Test
    @DisplayName("LLM 输出不可解析：落 review_unparseable，不返工")
    void shouldRecordUnparseableVerdict() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("乱七八糟的回复", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(null);

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_unparseable"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
    }

    @Test
    @DisplayName("判 pass：落 review_passed，闭环结束不返工")
    void shouldRecordPassVerdict() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":4}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_passed"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
    }

    @Test
    @DisplayName("驳回且未达返工上限：回调 rework 注入驳回意见")
    void shouldReworkOnRejectWithinAttemptLimit() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":false,\"score\":2}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(rejectedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_rejected"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskFinalReportService).rework(eq(TASK_ID),
                eq("1. 覆盖追溯表缺少子任务2的归属；3. 表格被截断"), eq(2));
    }

    @Test
    @DisplayName("驳回已达返工上限：不再重写，落 max_review_reached 保留当前报告")
    void shouldNotReworkWhenAttemptExceedsMaxReview() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":false,\"score\":2}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(rejectedVerdict());

        reviewService.onFinalReportGenerated(event(2));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_rejected"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_max_review_reached"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
    }

    @Test
    @DisplayName("配置 maxReview=0（关闭返工）：驳回直接落 max_review_reached")
    void shouldNotReworkWhenMaxReviewZero() {
        dispatchProperties.setAutoFinalReportMaxReview(0);
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":false,\"score\":2}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(rejectedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_max_review_reached"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskFinalReportService, never()).rework(any(), any(), anyInt());
    }

    // ---------- 机械前置门（3A-V2） ----------

    @Test
    @DisplayName("机械前置门：报告含空壳引用（见分册）→ 直接机械驳回，不调用审查 LLM")
    void shouldMechanicallyRejectOnShellReference() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID,
                "# 整合报告\n## 1. 开源矩阵\n完整 11×8 矩阵取值见分册第4章。\n"));

        reviewService.onFinalReportGenerated(event(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_rejected"), eq(AgentRole.REVIEWER), isNull(),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("source", "mechanical");
        assertThat(String.valueOf(payloadCaptor.getValue().get("issues"))).contains("shell_reference");
        // 机械门短路：不选人、不调 LLM；直接返工重写
        verify(reviewerPicker, never()).pickSingle(any());
        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(taskFinalReportService).rework(eq(TASK_ID), any(), anyInt());
    }

    @Test
    @DisplayName("机械前置门：软违规（缺覆盖追溯表）落 review_warned 告警，仍继续走 LLM 审查")
    void shouldWarnOnSoftViolationAndStillCallLlm() {
        when(taskService.getView(TASK_ID)).thenReturn(task(TASK_ID, "# 整合报告\n## 1. 甲\n本章讲甲。\n"));
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":4}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_warned"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_review_passed"), eq(AgentRole.REVIEWER),
                eq(REVIEWER_AGENT_ID), anyMap());
    }

    // ---------- 证据装配 ----------

    @Test
    @DisplayName("审查 Prompt 注入报告正文 + DONE 子任务产出证据（同口径事实源）")
    void shouldInjectReportAndSubTaskEvidenceIntoReviewPrompt() {
        TaskView task = taskView(TASK_ID, "# 整合报告正文：调度链路梳理", "调度分析", "梳理调度链路",
                FinalReportStatus.REVIEWING, REPORT_TIME);
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(reviewerPicker.pickSingle(any())).thenReturn(reviewer);
        when(subTaskService.listViewsByTaskId(TASK_ID)).thenReturn(List.of(
                subTask("子任务1: 梳理调度链路", "交付物: 链路图", "验收: 覆盖全链路"),
                subTask("子任务2: 整理参数表", "交付物: 参数表", "验收: 行数齐全")));
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("{\"pass\":true,\"score\":5}", "stop", "test-executor", 0));
        when(verdictParser.parseVerdict(any())).thenReturn(passedVerdict());

        reviewService.onFinalReportGenerated(event(1));

        ArgumentCaptor<AgentTask> agentTaskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(eq(REVIEWER_AGENT_ID), agentTaskCaptor.capture());
        String prompt = agentTaskCaptor.getValue().getUserPrompt();
        assertThat(prompt)
                .contains("调度分析")
                .contains("# 整合报告正文：调度链路梳理")
                .contains("子任务1: 梳理调度链路")
                .contains("子任务2: 整理参数表")
                .contains("交付物: 链路图")
                .contains("验收: 覆盖全链路")
                .contains("（产出概览：物化附件摘要）")
                .contains("覆盖追溯完整")
                .contains("无证据外提炼");
        // 审查判定复用 VerdictParser 可解析格式
        assertThat(agentTaskCaptor.getValue().getContext()).containsEntry("scene", "task_final_report_review");
    }

    // ---------- 私有装配 ----------

    private static TaskFinalReportGeneratedEvent event(int attempt) {
        return new TaskFinalReportGeneratedEvent(TASK_ID, 128, 3, attempt, REPORT_TIME);
    }

    /**
     * §12.5.5 #4：兜底落库写入器用<b>真实实例</b>包装 {@code taskService} / {@code taskTimelineService}
     * 两个 mock——写入委托到同一批 mock，故既有的 {@code verify(taskService).convergeFinalReportToDone(...)}
     * 与 {@code verify(taskTimelineService).recordEvent(...)} 断言全部保持不变（@Transactional 属容器语义，
     * 单测不启代理，只验证委托链路）。
     */
    private FinalReportReviewFallbackWriter fallbackWriter() {
        return new FinalReportReviewFallbackWriter(taskService, taskTimelineService);
    }

    private TaskView task(Long id, String finalReport) {
        return taskView(id, finalReport, "调度分析", "梳理调度链路",
                FinalReportStatus.REVIEWING, REPORT_TIME);
    }

    /** 全字段可控的任务视图（替代原实体的 setter 变更写法）。 */
    private TaskView taskView(Long id, String finalReport, String title, String description,
                              FinalReportStatus status, OffsetDateTime at) {
        // §12.2 状态守卫：审查体只在 REVIEWING 时继续（三路触发的幂等前提）
        return new TaskView(id, title, description, finalReport, status, WRITER_AGENT_ID, at, null);
    }

    private SubTaskView subTask(String title, String deliverable, String acceptance) {
        return new SubTaskView(null, TASK_ID, SubTaskStatus.DONE, null, null,
                title, null, deliverable, acceptance, null, null, List.of());
    }

    private static SubTaskReviewService.ReviewVerdict passedVerdict() {
        SubTaskReviewService.ReviewVerdict verdict = new SubTaskReviewService.ReviewVerdict();
        verdict.setPass(true);
        verdict.setScore(4);
        verdict.setComment("核验通过");
        return verdict;
    }

    private static SubTaskReviewService.ReviewVerdict rejectedVerdict() {
        SubTaskReviewService.ReviewVerdict verdict = new SubTaskReviewService.ReviewVerdict();
        verdict.setPass(false);
        verdict.setScore(2);
        verdict.setIssues("1. 覆盖追溯表缺少子任务2的归属；3. 表格被截断");
        return verdict;
    }
}
