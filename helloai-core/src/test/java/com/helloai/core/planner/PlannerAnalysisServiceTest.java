package com.helloai.core.planner;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.policy.SemanticContractGate;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.planner.service.PlanDraftRoundService;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.planner.service.impl.PlannerAnalysisServiceImpl;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.agent.port.SemanticJudgePort;
import com.helloai.core.planner.support.PlanGateRunner;
import com.helloai.core.task.support.SubTaskGateFindingsStore;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.port.UncertaintyView;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskDerivationService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.service.TaskRunningSpecService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerAnalysisService 单元测试（拆解异步化）：
 * decompose 同步守卫（校验 / CAS / 异步提交即返回 / 线程池拒绝回退）、
 * confirm / reject 状态流转。
 *
 * <p>LLM 拆解段用例（成功落库、JSON 解析失败、幽灵依赖、dependsOn 回写、
 * 门 1/2 批次门禁等）已迁至 {@code PlannerDecomposeAsyncServiceImplTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerAnalysisService")
class PlannerAnalysisServiceTest {

    private static final Long TASK_ID = 100L;

    @Mock
    private TaskService taskService;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private PlannerDecomposeAsyncService plannerDecomposeAsyncService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private SubTaskDispatchService subTaskDispatchService;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    @Mock
    private PlannerDecomposeProperties plannerDecomposeProperties;

    /** `TD-16b`：拆解入口的「状态变更 + Outbox 意图」原子写由它收口。 */
    @Mock
    private PlanAttemptService planAttemptService;

    /** `TD-16d`：拒绝草案要整轮作废暂存区。 */
    @Mock
    private PlanDraftRoundService planDraftRoundService;

    /** `TD-16d-4` 读侧换源：草案列表从暂存区取（无 ACTIVE 行时回落 sub_task）。 */
    @Mock
    private PlanDraftMapper planDraftMapper;

    /** `AD-5a-3b-1`：确认判定输入换源暂存区。 */
    @Mock
    private com.helloai.core.planner.service.PlanDraftService planDraftService;

    /** `AD-5a-3b-2b`：暂存区路径的 findings 写入。 */
    @Mock
    private com.helloai.core.planner.support.PlanDraftFindingsStore planDraftFindingsStore;

    /** `ADR-008` 草案期派生：建独立 Task `Y`。 */
    @Mock
    private TaskDerivationService taskDerivationService;

    /** `V150`：父任务拒绝重拆时关闭派生任务的派生会话。 */
    @Mock
    private com.helloai.core.planner.service.RequirementConversationService conversationService;

    /** 用真实实例（而非 Mock）：payload 形状与「写/清同源」正是被测语义。 */
    private PlanGateRunner planGateRunner;

    /**
     * 语义门用**真实实例 + 可编程端口**（不 mock 整个 gate）。
     *
     * <p>要测的是「命中 → 400 / 未执行 → 放行 / 无法定位 → 丢弃」三种结论的映射与落库，
     * mock 掉 gate 就只能验证「调过它」，测不到 findings 的形状与合并语义。</p>
     */
    private final AtomicReference<String> semanticReply = new AtomicReference<>("{\"conflicts\":[]}");

    private SemanticContractGate semanticContractGate;

    private PlannerAnalysisService plannerAnalysisService;

    /** 暂存区夹具：`stagedFrom(...)` 记录最近一次输入，供 `materializeFromStaging` 默认应答回放。 */
    private List<SubTaskView> lastStagedDrafts = List.of();

    // lambdaQuery / lambdaUpdate 链式 mock（项目内首例：手动 stub 链式返回自身）
    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTaskView> subTaskQueryChain = mock(LambdaQueryChainWrapper.class);

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<TaskView> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        planGateRunner = new PlanGateRunner(
                new SubTaskGateFindingsStore(subTaskService), taskTimelineService,
                plannerDecomposeProperties);
        semanticReply.set("{\"conflicts\":[]}");
        SemanticJudgePort semanticJudgePort = (agentId, systemPrompt, userPrompt) -> {
            String reply = semanticReply.get();
            if (reply == null) {
                throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agentId);
            }
            return reply;
        };
        semanticContractGate = new SemanticContractGate(semanticJudgePort, new ObjectMapper());
        plannerAnalysisService = new PlannerAnalysisServiceImpl(
                taskService, subTaskService, plannerDecomposeAsyncService,
                taskTimelineService, planGateRunner, semanticContractGate,
                subTaskDispatchService, taskRunningSpecService,
                plannerDecomposeProperties, planAttemptService, planDraftRoundService, planDraftMapper,
                planDraftService, planDraftFindingsStore, taskDerivationService, conversationService);

        lenient().when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(0L);
        lenient().when(subTaskService.countByTaskIdAndStatus(anyLong(), any())).thenReturn(0L);
        // 深度分档用真实表（`TD-16e-3`）：`maxDepthOf` 只有在 `getMaxExpandDepth()` 非 null 时才判深度，
        // mock 默认返回 null 会让确认路径静默跳过 `G3_DEPTH_LIMIT` —— 那正是「规则存在但路径上不生效」。
        lenient().when(plannerDecomposeProperties.getMaxExpandDepth())
                .thenReturn(new PlannerDecomposeProperties.MaxExpandDepth());

        // RM5 批 5a：状态推进改走 casStatus / updateStatus（原 lambdaUpdate 链已移除）；
        // 2026-11-08 起只保留四参重载（带 attemptId）——三参版本已删，避免「只比状态」的近路
        lenient().when(taskService.casStatusForAttempt(anyLong(), any(TaskStatus.class),
                any(TaskStatus.class), any())).thenReturn(true);
        // TD-16b：进入拆解改走 PlanAttemptService#beginDecompose —— 它内部完成
        // 「CAS 推进 PLANNING + 登记 task_plan_attempt」两笔同事务写，并返回 attemptId。
        // 测试里不再 stub taskService.beginDecompose：调用方已不直接调它。
        lenient().when(planAttemptService.beginDecompose(anyLong(), anyInt())).thenReturn("attempt-test");
        lenient().when(taskService.updateStatus(anyLong(), any(TaskStatus.class)))
                .thenReturn(org.mockito.Mockito.mock(com.helloai.core.task.entity.Task.class));
        // `AD-5a-5`：确认只走暂存区物化；默认回放最近一次 stagedFrom 的草案（需要失败的用例自行 doThrow 覆盖）
        lenient().when(subTaskService.materializeFromStaging(anyLong(), anyList(), anyMap(), any()))
                .thenAnswer(inv -> lastStagedDrafts);
    }

    /** 把执行表视图转成暂存区视图（`AD-5a-5` 后确认路径的唯一输入）；顺带记录回放列表。 */
    private List<com.helloai.core.planner.port.PlanDraftView> stagedFrom(List<SubTaskView> drafts) {
        lastStagedDrafts = drafts == null ? List.of() : drafts;
        Map<Long, String> codeById = new java.util.HashMap<>();
        int seq = 0;
        for (SubTaskView d : lastStagedDrafts) {
            codeById.put(d.id(), d.draftCode() != null ? d.draftCode()
                    : "20261108" + String.format("%04d", ++seq));
        }
        List<com.helloai.core.planner.port.PlanDraftView> out = new java.util.ArrayList<>();
        for (SubTaskView d : lastStagedDrafts) {
            String code = codeById.get(d.id());
            List<String> dependsOnCodes = d.dependsOn() == null ? List.of()
                    : d.dependsOn().stream().map(codeById::get)
                    .filter(java.util.Objects::nonNull).toList();
            out.add(new com.helloai.core.planner.port.PlanDraftView(
                    d.id(), TASK_ID, code, 1, null, d.title(), d.content(),
                    d.deliverable(), d.acceptance(),
                    d.acceptanceKind(),
                    TaskPriority.parse(d.priority()),
                    d.isContract(), d.requiredSkills(), d.constraints(),
                    List.of(), dependsOnCodes, d.context(), null));
        }
        return out;
    }

    private TaskView taskView(TaskStatus status, Integer slaMinutes) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                status, slaMinutes, null, null, null, null, null, null);
    }

    private TaskView pendingTask() {
        return taskView(TaskStatus.PENDING, null);
    }

    /** `V146` 起「草案待确认」是独立状态：confirm / reject 只认它，PLANNING 只表示拆解中。 */
    private TaskView awaitingConfirmTask() {
        return taskView(TaskStatus.AWAITING_PLAN_CONFIRM, null);
    }

    /** 真实形态草案：四要素齐全（confirm 前的门 2 复核依赖这些字段非空且 acceptance ≥ 10 字符）。 */
    /** 转正后的草案（确认事务已把它推进到 PENDING，分发只认这个状态）。 */
    private SubTaskView confirmedDraft(long id) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务" + id, "内容描述", "交付物完整清单", "运行 X 命令输出预期 Y 且字段存在", null, null, List.of());
    }

    private SubTaskView draft(long id) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务" + id, "内容描述", "交付物完整清单", "运行 X 命令输出预期 Y 且字段存在", null, null, List.of());
    }

    /** 带位置身份的草案（`TD-16d-2` 编辑/重生成路径的形态：draftCode 已在库）。 */
    private SubTaskView draftWithCode(long id, String code) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务" + id, "内容描述", "交付物完整清单", "运行 X 命令输出预期 Y 且字段存在", null, null, null, List.of(), List.of(), null, null, null, List.of(), null, null, null, null, null, null, null, code);
    }

    /** 暂存区行（`AD-5a-3b-2b`：新草案没有执行表副本，id 是 `task_plan_draft.id`）。 */
    private com.helloai.core.planner.port.PlanDraftView stagedView(long id, String code) {
        return new com.helloai.core.planner.port.PlanDraftView(id, TASK_ID, code, 1, 1,
                "暂存区草案" + id, "内容描述", "交付物完整清单", "运行 X 命令输出预期 Y 且字段存在",
                null, null, 0, List.of(), null, List.of(), List.of(), null, null);
    }

    /**
     * 语义门用草案：带 code、可控 acceptance / constraints / dependsOn，且 context 含
     * {@code plannerAgentId}（真实草案必有；缺了的话语义门会按「未执行」永久 fail-open，
     * 这条测试夹具本身就是在钉住那个前提）。
     */
    private SubTaskView semanticDraft(long id, String code, String acceptance, String constraints,
                                      List<Long> dependsOn) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务" + id, "内容描述", "交付物完整清单", acceptance, null, constraints, Map.of("plannerAgentId", 9001L), List.of(), dependsOn, null, null, null, List.of(), null, null, null, null, null, null, null, code);
    }

    private Page<SubTaskView> pageOf(List<SubTaskView> records) {
        Page<SubTaskView> page = new Page<>();
        page.setRecords(records);
        return page;
    }

    // ══════════════════════════════════════════════════════════════
    //  decompose：同步守卫 + 异步提交即返回
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正常拆解：校验 + CAS 推进 PLANNING 后提交异步执行，立即返回空列表")
    void shouldSubmitAsyncAndReturnEmpty() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());

        List<SubTaskView> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).isEmpty();
        // CAS 推进 PLANNING（与租约、attemptId 同一条 UPDATE）
        verify(planAttemptService).beginDecompose(eq(TASK_ID), anyInt());
        // 记录异步提交 timeline
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_async_submitted"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        // LLM 拆解转交异步服务（跨类调用激活 @Async 代理）
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("线程池拒绝：回退 PENDING 并抛『排队已满』业务异常")
    void shouldRollbackWhenExecutorRejects() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        doThrow(new TaskRejectedException("队列已满"))
                .when(plannerDecomposeAsyncService).executeDecompose(TASK_ID);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("排队已满");

        // 进入拆解一次 + 线程池拒绝后 CAS 回退一次
        verify(planAttemptService).beginDecompose(eq(TASK_ID), anyInt());
        verify(taskService).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
    }

    // ══════════════════════════════════════════════════════════════
    //  decompose：校验拒绝路径
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非 PENDING 任务拒绝拆解")
    void shouldRejectDecomposeWhenTaskNotPending() {
        TaskView task = taskView(TaskStatus.IN_PROGRESS, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PENDING");
        verify(planAttemptService, never()).beginDecompose(anyLong(), anyInt());
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    @Test
    @DisplayName("已存在非 CANCELLED 子任务时拒绝重复拆解")
    void shouldRejectDecomposeWhenSubTasksAlreadyExist() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(3L);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许重复拆解");
        verify(planAttemptService, never()).beginDecompose(anyLong(), anyInt());
        // §6.100: 有非 CANCELLED 残留时不得触碰物理删除
        verify(subTaskService, never()).physicalDeleteNeverExecutedCancelled(anyLong());
    }

    @Test
    @DisplayName("§6.100: 仅残留 CANCELLED 旧草案时，拆解前物理删除再提交异步")
    void shouldPhysicallyDeleteCancelledDraftsBeforeRedecompose() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        // 第一次 count（非 CANCELLED）= 0，第二次 count（CANCELLED）= 2，按调用顺序 stub
        when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(0L);
        when(subTaskService.countByTaskIdAndStatus(anyLong(), any())).thenReturn(2L);
        when(subTaskService.physicalDeleteNeverExecutedCancelled(anyLong())).thenReturn(2);

        List<SubTaskView> drafts = plannerAnalysisService.decompose(TASK_ID);

        // 物理删除发生在提交异步前，同步守卫正常返回空列表
        verify(subTaskService).physicalDeleteNeverExecutedCancelled(TASK_ID);
        assertThat(drafts).isEmpty();
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("§6.100 补：部分 CANCELLED 旧草案有执行痕迹被保留 → 记保留事件")
    void shouldRecordRetainedWhenSomeCancelledDraftsNeverDeleted() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(0L);
        when(subTaskService.countByTaskIdAndStatus(anyLong(), any())).thenReturn(3L);
        // 3 条 CANCELLED 只删掉 2 条：另 1 条有 agent_execution_record，删了会留孤儿引用
        when(subTaskService.physicalDeleteNeverExecutedCancelled(anyLong())).thenReturn(2);

        plannerAnalysisService.decompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_cancelled_draft_retained"),
                eq(AgentRole.PLANNER), isNull(), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("cancelled", 3L)
                .containsEntry("deleted", 2)
                .containsEntry("retained", 1L);
    }

    @Test
    @DisplayName("§6.100: 无 CANCELLED 残留时不触发物理删除（正常首次拆解不受影响）")
    void shouldNotDeleteWhenNoCancelledDrafts() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());

        List<SubTaskView> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).isEmpty();
        verify(subTaskService, never()).physicalDeleteNeverExecutedCancelled(anyLong());
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("CAS 失败（并发拆解中）时拒绝，不提交异步")
    void shouldRejectWhenCasLost() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        when(planAttemptService.beginDecompose(anyLong(), anyInt())).thenReturn(null);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他请求拆解中");
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    // ══════════════════════════════════════════════════════════════
    //  confirmPlan / rejectPlan
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("confirm：草案批量转正 PENDING，Task → IN_PROGRESS，无条件逐条分发")
    void shouldConfirmDraftsAndDispatch() {
        TaskView task = awaitingConfirmTask();
        when(taskService.getView(TASK_ID)).thenReturn(task);
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(drafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> confirmedDraft(inv.getArgument(0)));

        List<SubTaskView> confirmed = plannerAnalysisService.confirmPlan(TASK_ID);

        assertThat(confirmed).hasSize(2);
        // `TD-16e`：转正收成一次整批调用（一个事务），不再逐条 changeStatus
        verify(subTaskService).materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), isNull());
        verify(taskService).updateStatus(TASK_ID, TaskStatus.IN_PROGRESS);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_confirmed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(1L, AgentRole.EXECUTOR);
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(2L, AgentRole.EXECUTOR);
        // `TD-16d-4` 读侧换源前置：确认成功后暂存区整轮置 CONFIRMED（草案页不再展示已转正草案）
        verify(planDraftRoundService).confirmRound(TASK_ID);
    }


    @Test
    @DisplayName("confirm：转正失败（整批回滚）时不得推进任务 / 清 findings / 置 CONFIRMED / 派发")
    void shouldNotAdvanceTaskWhenDraftConfirmationFails() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of(draft(1L), draft(2L))));
        // 模拟事务内第二条撞「非法状态转换」（并发确认）⇒ 整批回滚
        when(subTaskService.materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), any()))
                .thenThrow(new BizException("非法状态转换: PENDING -> PENDING"));

        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法状态转换");

        // 这就是「半成功」的反面：写入段失败后，收尾动作一个都不能发生
        verify(taskService, never()).updateStatus(anyLong(), any(TaskStatus.class));
        verify(planDraftRoundService, never()).confirmRound(anyLong());
        verify(subTaskService, never()).updateContextInNewTx(anyLong(), anyMap());
        verify(subTaskDispatchService, never()).dispatchPendingSubTaskAuto(anyLong(), any(AgentRole.class));
    }

    @Test
    @DisplayName("confirm：成功后清各草案的 sub_task.context.gateFindings（不残留 phase=confirm 的阻断记录）")
    void shouldClearGateFindingsAfterConfirm() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of(draft(1L))));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));

        plannerAnalysisService.confirmPlan(TASK_ID);

        // 2026-11-08 草案实体化：findings 落在草案上 ⇒ 清理也必须逐草案写，任务级不再持有
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskService).updateContextInNewTx(eq(1L), captor.capture());
        verify(taskService, never()).updateContextInNewTx(anyLong(), anyMap());
        @SuppressWarnings("unchecked")
        Map<String, Object> cleared = (Map<String, Object>) captor.getValue()
                .get(SubTaskContextKeys.GATE_FINDINGS);
        // 消费方按 findings 为空判「无阻断」，残留一条 block 会被读成「仍有未解」
        assertThat(cleared).containsEntry("ruleVersion", DraftGateChecker.RULE_VERSION)
                .containsKey("clearedAt");
        assertThat((List<?>) cleared.get("findings")).isEmpty();
    }

    @Test
    @DisplayName("confirm：单条分发失败不阻断确认与其余分发")
    void shouldConfirmEvenWhenDispatchFails() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(drafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> confirmedDraft(inv.getArgument(0)));
        doThrow(new BizException("无可用 Agent"))
                .when(subTaskDispatchService).dispatchPendingSubTaskAuto(1L, AgentRole.EXECUTOR);

        List<SubTaskView> confirmed = plannerAnalysisService.confirmPlan(TASK_ID);

        assertThat(confirmed).hasSize(2);
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(2L, AgentRole.EXECUTOR);
    }

    @Test
    @DisplayName("confirm：任务带 SLA 时把 slaMinutes 交给转正事务，由服务内先落 deadline 再转正")
    void shouldAssignDeadlineFromTaskSlaWhenConfirming() {
        TaskView task = taskView(TaskStatus.AWAITING_PLAN_CONFIRM, 60);
        when(taskService.getView(TASK_ID)).thenReturn(task);
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(drafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));

        plannerAnalysisService.confirmPlan(TASK_ID);

        // deadline 的落库顺序（先 updateById 再 changeStatus）已收进服务事务，
        // 由 `SubTaskServiceConfirmDraftsTest#shouldConfirmAllInOrder` 用 InOrder 钉住；
        // 本层只保证「SLA 确实传下去了」——传丢就等于不下发 deadline，且静默。
        verify(subTaskService).materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), eq(60));
    }

    @Test
    @DisplayName("confirm：非 PLANNING 状态或无草案时拒绝")
    void shouldRejectConfirmOnIllegalState() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可确认草案")
                // 状态门是用户可纠正的拒绝，必须是 400：单参构造会变 500，前端当成服务端故障
                // （2026-11-09 真机重复 confirm 实测 code=500）
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);

        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of()));
        // recoverAlreadyConfirmed 会查 PENDING 子任务，未 stub 则返回 null 导致 NPE
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING))
                .thenReturn(List.of());
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有待确认的规划草案");
    }

    @Test
    @DisplayName("confirm：草案被改坏（acceptance 过短）→ 确认前门 2 复核阻断，不转正，审计走独立事务")
    void shouldBlockConfirmWhenDraftFieldRuleFails() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        // 模拟 updateDraft 之后 acceptance 被改短：S4 的拆解期结论已过期，确认时必须复核
        SubTaskView broken = new SubTaskView(1L, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务1", "内容描述", "交付物完整清单", "通过", null, null, List.of());
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of(broken, draft(2L))));

        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("G2_LEN_MIN");

        verify(subTaskService, never()).changeStatus(anyLong(), any(), any(), anyMap());
        verify(taskService, never()).updateStatus(anyLong(), any());
        verify(taskTimelineService).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        // 结论落到暂存区整批（含未命中草案的清零 payload）
        verify(planDraftFindingsStore).persist(eq(TASK_ID), anyList(), any());
    }

    @Test
    @DisplayName("confirm：语义门命中 G4（验收撞上游约束）→ 400，草案不转正，结论落在命中草案上")
    void shouldBlockConfirmWhenSemanticGateFindsContractConflict() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        SubTaskView upstream = semanticDraft(1L, "202611090001",
                "运行 X 命令输出预期 Y 且字段存在", "不得引用任何外部资料，不得联网检索", List.of());
        SubTaskView downstream = semanticDraft(2L, "202611090001-001",
                "必须引用至少 3 条外部资料并逐条附可打开 URL", "不得编造未确认的事实", List.of(1L));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of(upstream, downstream)));
        semanticReply.set("{\"conflicts\":[{\"draftRef\":\"202611090001-001\",\"upstreamRef\":\"202611090001\","
                + "\"acceptanceQuote\":\"必须引用至少 3 条外部资料并逐条附可打开 URL\","
                + "\"constraintQuote\":\"不得引用任何外部资料，不得联网检索\","
                + "\"reason\":\"下游验收要求引用外部资料，上游约束禁止引用\"}]}");

        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("G4_UNSATISFIABLE_CONTRACT")
                .hasMessageContaining("不可能同时满足")
                // 用户可纠正的拒绝必须 400：单参构造会变 500，前端当成服务端故障（判据 22）
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);

        verify(subTaskService, never()).materializeFromStaging(anyLong(), anyList(), anyMap(), any());
        verify(taskService, never()).updateStatus(anyLong(), any());
        // 结论必须落库，否则用户看不到「哪条草案撞了哪条上游约束」
        verify(planDraftFindingsStore).persist(eq(TASK_ID), anyList(), any());
    }

    @Test
    @DisplayName("confirm：语义门不可用（模型调用异常）→ fail-open 放行，但必须留「未执行」痕迹")
    void shouldFailOpenWhenSemanticGateUnavailable() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        List<SubTaskView> drafts = List.of(
                semanticDraft(1L, "202611090001", "运行 X 命令输出预期 Y 且字段存在", "不得引用外部资料", List.of()),
                semanticDraft(2L, "202611090001-001", "输出一份 3 节的结构化报告", "不得编造数据", List.of(1L)));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(drafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));
        // 端口抛异常 = 无可用凭据 / Agent 不存在
        semanticReply.set(null);

        plannerAnalysisService.confirmPlan(TASK_ID);

        // fail-open：确认照常走完
        verify(subTaskService).materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), isNull());
        // 但「语义门未执行」必须落库可见（warn 级）
        ArgumentCaptor<DraftGateChecker.Result> captor = ArgumentCaptor.forClass(DraftGateChecker.Result.class);
        verify(planDraftFindingsStore).persist(eq(TASK_ID), anyList(), captor.capture());
        assertThat(captor.getValue().findings().stream()
                .anyMatch(f -> String.valueOf(f).contains("语义门未执行")))
                .as("fail-open 不能静默：warn 结论必须写进 gateFindings")
                .isTrue();
    }

    /**
     * 2026-11-09 真机回归：warn 写回 findings 走 {@code updateById}，会刷新 {@code update_time}，
     * 而 {@code confirmDrafts} 用它做「确认期间被改」判定——基准不挪到复核之后，任何带 warn 的
     * 批次都会 500 且重试永远失败（{@code TD13S2真机验证} 复现）。
     */
    @Test
    @DisplayName("confirm：warn 落库后仍以同一份暂存区快照转正（不重取第二份快照）")
    void shouldConfirmUsingStagedSnapshotWhenWarnPersisted() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        List<SubTaskView> stagedDrafts = List.of(
                semanticDraft(1L, "202611090001", "运行 X 命令输出预期 Y 且字段存在", "不得引用外部资料", List.of()),
                semanticDraft(2L, "202611090001-001", "输出一份 3 节的结构化报告", "不得编造数据", List.of(1L)));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(stagedDrafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));
        semanticReply.set(null); // fail-open → warn 落库

        plannerAnalysisService.confirmPlan(TASK_ID);

        verify(subTaskService).materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), isNull());
    }

    @Test
    @DisplayName("confirm：模型给出本批不存在的 draftRef → 丢弃该冲突并放行（不产生无法定位的阻断）")
    void shouldDropConflictWithUnknownDraftRef() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        List<SubTaskView> drafts = List.of(
                semanticDraft(1L, "202611090001", "运行 X 命令输出预期 Y 且字段存在", "不得引用外部资料", List.of()),
                semanticDraft(2L, "202611090001-001", "输出一份 3 节的结构化报告", "不得编造数据", List.of(1L)));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(drafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));
        // 模型把 draftRef 写成了标题（很常见），定位不到任何草案 ⇒ 采纳会得到用户无法操作的 blocking
        semanticReply.set("{\"conflicts\":[{\"draftRef\":\"子任务2\",\"upstreamRef\":\"202611090001\","
                + "\"acceptanceQuote\":\"输出一份 3 节的结构化报告\",\"constraintQuote\":\"不得编造数据\","
                + "\"reason\":\"臆测\"}]}");

        plannerAnalysisService.confirmPlan(TASK_ID);

        verify(subTaskService).materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), isNull());
    }

    @Test
    @DisplayName("confirm：整批没有依赖关系时不做语义判定（无上游约束这个输入，不烧模型调用）")
    void shouldSkipSemanticGateWhenNoDependency() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(drafts));
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));
        // 即使模型「想报」冲突，没有上游就没有可比对的约束 ⇒ 不该调模型、更不该阻断
        semanticReply.set("{\"conflicts\":[{\"draftRef\":\"1\",\"upstreamRef\":\"2\","
                + "\"acceptanceQuote\":\"a\",\"constraintQuote\":\"b\",\"reason\":\"c\"}]}");

        plannerAnalysisService.confirmPlan(TASK_ID);

        verify(subTaskService).materializeFromStaging(eq(TASK_ID), anyList(), anyMap(), isNull());
    }

    @Test
    @DisplayName("confirm：复核后暂存区内容被改 ⇒ 400 漂移拒绝，不物化")
    void shouldRejectWhenStagingChangedAfterRecheck() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        List<SubTaskView> before = List.of(draft(1L), draft(2L));
        SubTaskView edited = new SubTaskView(2L, TASK_ID, SubTaskStatus.PENDING, null, null, "子任务2", "内容描述", "交付物完整清单", "验收被改成完全不同的写法", null, null, List.of());
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(before), stagedFrom(List.of(draft(1L), edited)));

        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("确认期间被修改")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);

        verify(subTaskService, never()).materializeFromStaging(anyLong(), anyList(), anyMap(), any());
    }

    @Test
    @DisplayName("reject：草案全部翻 CANCELLED，Task 回退 PENDING")
    void shouldRejectDraftsAndRollbackTask() {
        TaskView task = awaitingConfirmTask();
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of(draft(1L), draft(2L), draft(3L))));

        int cancelled = plannerAnalysisService.rejectPlan(TASK_ID);

        assertThat(cancelled).isEqualTo(3);
        verify(taskService).updateStatus(TASK_ID, TaskStatus.PENDING);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_rejected"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        // `TD-16d`：拒绝必须整轮作废暂存区。
        // 不清的话，第一轮的 ACTIVE 行仍占着 `uk_plan_draft_code`（`WHERE deleted = 0`），
        // 第二轮拆解产出的 code 与之重叠 ⇒ 撞唯一索引 ⇒ 被上层当成「幂等命中」吞掉
        // ⇒ 任务永久卡在 PLANNING（2026-11-08 23:13 真机复现）。
        verify(planDraftRoundService).discardRound(TASK_ID);
    }

    @Test
    @DisplayName("reject：级联取消派生任务，并关闭它的派生会话（V150）")
    void shouldCancelDerivedTaskAndCloseItsConversation() {
        TaskView task = awaitingConfirmTask();
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(planDraftService.listByTask(TASK_ID)).thenReturn(stagedFrom(List.of(draft(1L))));
        long derivedTaskId = 888L;
        // parent_sub_task_id 为空的 TaskView 构造走兼容重载，这里只关心 id
        when(taskDerivationService.listActivePlanDerivations(TASK_ID))
                .thenReturn(List.of(new TaskView(derivedTaskId, "派生任务", "d", null, null, null,
                        null, Map.of(), TaskStatus.IN_PROGRESS, null, null, Map.of(), List.of(),
                        null, null, null, 1, TASK_ID)));
        when(conversationService.abandonByDerivedTask(derivedTaskId)).thenReturn(true);

        plannerAnalysisService.rejectPlan(TASK_ID);

        verify(taskService).updateStatus(derivedTaskId, TaskStatus.CANCELLED);
        // 产出任务被取消后会话若仍 FINALIZED：「查看任务」指向已取消任务、「重新生成」必然 400
        verify(conversationService).abandonByDerivedTask(derivedTaskId);
    }

    @Test
    @DisplayName("reject：非 PLANNING 状态拒绝")
    void shouldRejectRejectPlanOnIllegalState() {
        TaskView task = taskView(TaskStatus.DONE, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> plannerAnalysisService.rejectPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可拒绝草案");
    }

    // ══════════════════════════════════════════════════════════════
    //  d-3b-2：草案写路径按位置编号 code 定位（翻译点只有 resolveDraftId 一处）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("updateDraftByCode：code 查不到（已展开/已转正/非本任务）⇒ 400，且不碰执行表")
    void shouldFailFastWhenCodeMisses() {
        assertThatThrownBy(() -> plannerAnalysisService.updateDraftByCode(TASK_ID, "202611080099",
                null, null, null, "AUTOMATED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草案定位失败")
                .hasMessageContaining("202611080099")
                // HTTP 状态是这一层的契约：单参 BizException 会变 500，前端就看不到「草案已被取代」
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(400);

    }

    @Test
    @DisplayName("deriveDraftByCode：code 命中 ⇒ 翻译成行 id 后走派生编排（已有活跃派生则幂等返回）")
    void shouldResolveCodeThenDelegateDerive() {
        when(planDraftService.getByCode(TASK_ID, "202611080002")).thenReturn(stagedView(5L, "202611080002"));
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.AWAITING_PLAN_CONFIRM, null));
        TaskView derived = new TaskView(71L, "派生任务", "描述", null, null, null, null, null,
                TaskStatus.IN_PROGRESS, null, null, null, null, null, null, null);
        when(taskDerivationService.findActivePlanDerivation(TASK_ID, "202611080002")).thenReturn(derived);

        TaskView result = plannerAnalysisService.deriveDraftByCode(TASK_ID, "202611080002");

        assertThat(result.id()).isEqualTo(71L);
        verify(taskDerivationService, never()).createPlanDerivation(anyLong(), anyString(), any());
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    @Test
    @DisplayName("regenerateDraftByCode：code 查不到 ⇒ 400，不触发 LLM 重生成（避免白烧额度）")
    void shouldNotRegenerateWhenCodeMisses() {
        assertThatThrownBy(() -> plannerAnalysisService.regenerateDraftByCode(TASK_ID, "202611080002-001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草案定位失败");


    }

    @Test
    @DisplayName("AD-5a-3b-2b：新草案没有执行表副本 ⇒ 重生成直达暂存区，不按副本 id 重生成")
    void shouldRegenerateStagingWhenNoCopyExists() {
        when(planDraftService.getByCode(TASK_ID, "202611080003")).thenReturn(stagedView(9L, "202611080003"));
        when(plannerDecomposeAsyncService.regenerateStaged(TASK_ID, "202611080003"))
                .thenReturn(draftWithCode(9L, "202611080003"));

        plannerAnalysisService.regenerateDraftByCode(TASK_ID, "202611080003");

        verify(plannerDecomposeAsyncService).regenerateStaged(TASK_ID, "202611080003");

    }

    @Test
    @DisplayName("AD-5a-3b-2b：新草案没有执行表副本 ⇒ 派生直接以暂存区行为父锚，时间线不带伪造 subTaskId")
    void shouldDeriveStagingWhenNoCopyExists() {
        when(planDraftService.getByCode(TASK_ID, "202611080003")).thenReturn(stagedView(9L, "202611080003"));
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.AWAITING_PLAN_CONFIRM, null));
        TaskView derived = new TaskView(71L, "派生任务", "描述", null, null, null, null, null,
                TaskStatus.IN_PROGRESS, null, null, null, null, null, null, null);
        when(taskDerivationService.findActivePlanDerivation(TASK_ID, "202611080003")).thenReturn(null);
        when(taskDerivationService.createPlanDerivation(eq(TASK_ID), eq("202611080003"), any()))
                .thenReturn(derived);

        TaskView result = plannerAnalysisService.deriveDraftByCode(TASK_ID, "202611080003");

        assertThat(result.id()).isEqualTo(71L);
        verify(taskDerivationService).createPlanDerivation(eq(TASK_ID), eq("202611080003"), any());
        // 暂存区 id 与 sub_task.id 是两套空间：时间线不能拿它当 subTaskId
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_plan_draft_derived"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("updateDraftByCode 必须自带 @Transactional：自调用会跳过 updateDraft 上的注解")
    void shouldDeclareTransactionOnUpdateDraftByCode() throws NoSuchMethodException {
        // 2026-11-09 真机 500 的守卫：本类内部 updateDraftByCode → updateDraft 是自调用，
        // Spring AOP 不会给内层开事务，下游暂存区写入（MANDATORY）就抛 IllegalTransactionStateException。
        // 裸对象单测测不出事务代理，只能断言「注解在方法上」这一形式契约。
        var m = PlannerAnalysisServiceImpl.class.getMethod("updateDraftByCode",
                Long.class, String.class, List.class, String.class, List.class, String.class);
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("updateDraftByCode 丢了 @Transactional 就会 500（暂存区写入 MANDATORY 无外层事务）")
                .isTrue();
    }


    // ══════════════════════════════════════════════════════════════
    //  `AD-5a-5`：暂存区唯一读路 / 编辑 / 重生成
    // ══════════════════════════════════════════════════════════════

    /** 读侧夹具：构造暂存区实体行（内容与执行表无关）。 */
    private PlanDraft mirrorRow(long id, String code, Integer draftSeq, String title) {
        PlanDraft d = new PlanDraft();
        d.setId(id);
        d.setTaskId(TASK_ID);
        d.setCode(code);
        d.setTitle(title);
        d.setContent("暂存区内容-" + id);
        d.setDeliverable("暂存区交付物-" + id);
        d.setAcceptance("暂存区验收标准-" + id);
        d.setConstraints("暂存区约束-" + id);
        d.setDraftSeq(draftSeq);
        d.setPriority(TaskPriority.MEDIUM);
        d.setContract(0);
        d.setRequiredSkills(List.of("java"));
        d.setUncertainties(List.of(new UncertaintyDraft("ASSUMPTION", "暂存区假设-" + id)));
        return d;
    }

    @Test
    @DisplayName("listDraftsFromStaging：无当前轮（round=0）⇒ 空列表，不回落执行表")
    void shouldReturnEmptyWhenNoStagingRound() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(0);

        assertThat(plannerAnalysisService.listDraftsFromStaging(TASK_ID)).isEmpty();
        verify(planDraftMapper, never()).listActiveIdsByRound(anyLong(), anyInt());
    }

    @Test
    @DisplayName("listDraftsFromStaging：按 code 树序返回暂存区视图，依赖翻译成同批 id")
    void shouldReturnStagingViewsInCodeOrder() {
        when(taskService.getView(TASK_ID)).thenReturn(awaitingConfirmTask());
        when(planDraftMapper.findMaxActiveRound(TASK_ID)).thenReturn(1);
        when(planDraftMapper.listActiveIdsByRound(TASK_ID, 1)).thenReturn(List.of(700L, 701L));
        PlanDraft first = mirrorRow(700L, "202611080001", 1, "根1");
        PlanDraft second = mirrorRow(701L, "202611080002", 2, "根2");
        second.setDependsOnCodes(List.of("202611080001"));
        // selectBatchIds 不保证顺序：结果顺序必须由 rowIds（code 序）决定
        when(planDraftMapper.selectBatchIds(List.of(700L, 701L))).thenReturn(List.of(second, first));

        List<SubTaskView> list = plannerAnalysisService.listDraftsFromStaging(TASK_ID);

        assertThat(list).extracting(SubTaskView::id).containsExactly(700L, 701L);
        assertThat(list).extracting(SubTaskView::draftCode)
                .containsExactly("202611080001", "202611080002");
        assertThat(list.get(1).dependsOn()).containsExactly(700L);
    }

    @Test
    @DisplayName("updateDraftByCode：暂存区命中 ⇒ 直接写暂存区，不碰执行表")
    void shouldUpdateStagedDraftByCode() {
        when(planDraftService.getByCode(TASK_ID, "202611080003")).thenReturn(stagedView(9L, "202611080003"));

        plannerAnalysisService.updateDraftByCode(TASK_ID, "202611080003",
                List.of("skill-a"), "约束", List.of(), "STRUCTURAL");

        verify(planDraftService).updateByCode(eq(TASK_ID), eq("202611080003"), isNull(), isNull(), isNull(),
                eq(List.of("skill-a")), eq("约束"), eq(List.of()), eq("STRUCTURAL"));
    }

    @Test
    @DisplayName("updateDraftByCode：草案不存在 ⇒ 400")
    void shouldRejectUpdateWhenStagedDraftMissing() {
        when(planDraftService.getByCode(TASK_ID, "202611080099")).thenReturn(null);

        assertThatThrownBy(() -> plannerAnalysisService.updateDraftByCode(TASK_ID, "202611080099",
                null, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草案定位失败");
    }

    @Test
    @DisplayName("regenerateDraftByCode：暂存区命中 ⇒ regenerateStaged")
    void shouldRegenerateStagedDraft() {
        when(planDraftService.getByCode(TASK_ID, "202611080003")).thenReturn(stagedView(9L, "202611080003"));
        SubTaskView regenerated = draftWithCode(9L, "202611080003");
        when(plannerDecomposeAsyncService.regenerateStaged(TASK_ID, "202611080003")).thenReturn(regenerated);

        assertThat(plannerAnalysisService.regenerateDraftByCode(TASK_ID, "202611080003")).isSameAs(regenerated);
    }

}
