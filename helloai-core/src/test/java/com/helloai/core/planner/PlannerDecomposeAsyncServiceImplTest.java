package com.helloai.core.planner;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.PlanAttemptStatus;
import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.planner.context.DecomposeContextSnapshotRenderer;
import com.helloai.core.planner.entity.PlanAttempt;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.common.util.ExecutionEnv;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.policy.TaskContextKeys;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.planner.service.PlanDraftRoundService;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.impl.PlannerDecomposeAsyncServiceImpl;
import com.helloai.core.planner.support.PlanDraftFindingsStore;
import com.helloai.core.planner.support.PlanGateRunner;
import com.helloai.core.task.support.SubTaskGateFindingsStore;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.port.UncertaintyView;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.port.PlannerAgentRef;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerDecomposeAsyncServiceImpl 单元测试（拆解异步化改造，LLM 段用例迁移自
 * PlannerAnalysisServiceTest）：幂等守卫 / 成功落库 / markdown fence 容错 /
 * JSON 解析失败回退 / LLM 调用失败回退 / 幽灵依赖 / dependsOn 回写 /
 * 门 1/2 批次门禁 / G-011 需求包渲染与 uncertainties 管理。
 *
 * <p>异步方法在测试中同步直调（不经 Spring 代理），失败路径内部闭环不抛异常，
 * 断言回退 PENDING（lambdaUpdate）与 task_plan_failed timeline。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerDecomposeAsyncServiceImpl")
class PlannerDecomposeAsyncServiceImplTest {

    private static final Long TASK_ID = 100L;

    @Mock
    private TaskService taskService;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private PlannerAgentPicker plannerAgentPicker;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private AgentService agentService;

    @Mock
    private AgentSkillSpecService agentSkillSpecService;

    /** `TD-6` 快照渲染器：本测试只关心「拆解不因快照失败而失败」，故不打桩（默认返回 null 已验证降级路径）。 */
    @Mock
    private DecomposeContextSnapshotRenderer contextSnapshotRenderer;

    /** `TD-16b` 尝试栅栏：旁挂设施，默认无记录（见 setUp 的 lenient stub）。 */
    @Mock
    private PlanAttemptService planAttemptService;

    /** `TD-16c` 暂存区写入/轮次：本测试只关心主流程编排，写入与轮次细节由 `PlanDraftRoundServiceImplTest` 覆盖。 */
    @Mock
    private PlanDraftRoundService planDraftRoundService;

    /** `TD-16d` `B4`：根层 code 序号取任务内已用最大值（默认桩 0 ⇒ 从 1 起）。 */
    @Mock
    private PlanDraftMapper planDraftMapper;

    /** `AD-5a-3b-2b`：拆解落库段以暂存区为唯一事实源。 */
    @Mock
    private PlanDraftService planDraftService;

    /** 展开深度分档（设计 §7）：用真实实例——分档表本身就是被测语义，mock 掉断言会退化。 */
    private final com.helloai.common.config.PlannerDecomposeProperties plannerDecomposeProperties =
            new com.helloai.common.config.PlannerDecomposeProperties();

    private PlannerDecomposeAsyncServiceImpl asyncService;

    /**
     * 单测没有容器，{@link org.springframework.transaction.support.TransactionTemplate} 需要一个
     * 真能返回事务状态的管理器。这里用一个「什么都不做」的实现：commit / rollback 都是空操作，
     * 因此用例看到的是**回调本身的返回值**，而不是真实事务语义——这正是我们要测的
     * （落库段的编排与状态分流），事务回滚本身由 Spring 保证。
     */
    private final PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op：单测不验证真实提交
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op：单测不验证真实回滚
        }
    };


    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）；
        // gateFindings 存储与门禁编排同样用真实实例：payload 形状、「写/清同源」、结论挂哪条草案
        // 正是被测语义（mock 掉会让断言退化成「有没有调用某个方法」）。
        asyncService = new PlannerDecomposeAsyncServiceImpl(
                taskService, subTaskService, plannerAgentPicker,
                new PlanGateRunner(new SubTaskGateFindingsStore(subTaskService), taskTimelineService,
                        plannerDecomposeProperties),
                platformAgentExecutionService, taskTimelineService,
                agentService, agentSkillSpecService, contextSnapshotRenderer, new ObjectMapper(),
                transactionManager, planAttemptService, planDraftRoundService, planDraftMapper,
                planDraftService, new PlanDraftFindingsStore(planDraftMapper));

        // 暂存区 findings 存储用真实实现（写/清同源是被测语义）；mapper 用最小内存桩承接读改写
        lenient().when(planDraftMapper.selectById(anyLong())).thenAnswer(inv -> {
            PlanDraft row = new PlanDraft();
            row.setId(inv.getArgument(0));
            row.setTaskId(TASK_ID);
            return row;
        });

        // TD-16b：栅栏是旁挂设施，默认「没有 attempt 记录」⇒ 所有栅栏操作静默跳过，
        // 既有用例断言的主流程行为不受影响。需要验证栅栏语义的用例自己 stub。
        lenient().when(planAttemptService.findLatestByBatchKey(anyString()))
                .thenReturn(java.util.Optional.empty());

        // V146：异步段只读入口写好的 attemptId，读不到就直接跳过（不擅自补 UUID）
        lenient().when(taskService.currentDecomposeAttempt(anyLong())).thenReturn("attempt-test");
        // 拆解成功后的状态推进：PLANNING → AWAITING_PLAN_CONFIRM
        // 注意用四参版本：三参 casStatus 只比状态，挡不住「上一轮拆解唤醒后命中下一轮的 PLANNING」
        lenient().when(taskService.casStatusForAttempt(anyLong(),
                        eq(TaskStatus.PLANNING), eq(TaskStatus.AWAITING_PLAN_CONFIRM), anyString()))
                .thenReturn(true);

    }

    private TaskView planningTask() {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                TaskStatus.PLANNING, null, null, null, null, null, null, null);
    }

    /** 带固定创建时刻的拆解中任务（`code` 的日期前缀由它决定，不能靠 {@code now()}）。 */
    private TaskView planningTaskAt(OffsetDateTime created) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                TaskStatus.PLANNING, null, null, null, null, created, created, null);
    }

    /** 任务快照（不可变，等价原 setStatus/setPriority 写法）。 */
    private TaskView taskView(TaskStatus status, String priority) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                status, null, priority, null, null, null, null, null);
    }

    /** 重加载草案快照（含优先级与 context，等价原 setPriority/setContext 写法）。 */
    private SubTaskView draftFull(long id, String priority, Map<String, Object> context) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, null, null, null, null, null, null, context, List.of(), null, null, priority, null, null, null, null, null, null, null, null, null, null);
    }

    private SubTaskView draftWithPriority(long id, String priority) {
        return draftFull(id, priority, null);
    }

    /** 带 agent_policy 的任务快照（不可变，等价原 setAgentPolicy 写法）。 */
    private TaskView taskWithPolicy(Map<String, Object> agentPolicy) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null,
                agentPolicy, TaskStatus.PLANNING, null, null, null, null, null, null, null);
    }

    /**
     * RM6 端口契约去实体：{@code pickForTask} 现返回只读投影 {@link PlannerAgentRef}，
     * 测试替身随之改返回值对象（id + name）。
     */
    private PlannerAgentRef llmPlanner() {
        return new PlannerAgentRef(9L, "planner-llm");
    }

    /**
     * 「门禁视为合格」的草案视图：四要素齐全、acceptance 含命令 + 预期结果（AUTOMATED Pass）。
     *
     * <p>2026-11-08 起门禁判的是**已落库草案的字段**，不再是 LLM 输出文本；reload 桩若仍是全 null，
     * 每条草案都会命中 {@code G2_BLANK}，用例就变成「验阻断」而不是原意。故默认给合规值，
     * 需要构造不合格草案的用例显式用 {@link #draftBroken} / {@link #draftWithKind}。</p>
     */
    private SubTaskView draft(long id) {
        return draftFullTD9(id, "草案" + id, "内容说明" + id, "交付物清单" + id,
                "运行 pytest 输出 5 passed", List.of());
    }

    /**
     * 指定 acceptance 文本与显式 kind 声明的草案视图（门 2 边界用例专用）。
     *
     * @param declaredKind 显式声明的 kind 原文；{@code null} = 未声明（走 Warn 放行路径）
     */
    private SubTaskView draftWithKind(long id, String acceptance, String declaredKind, List<Long> dependsOn) {
        Map<String, Object> context = new HashMap<>();
        if (declaredKind != null) {
            context.put(SubTaskContextKeys.ACCEPTANCE_KIND_DECLARED, declaredKind);
        }
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, "写文档", "撰写交付文档", "交付文档成品", acceptance, null, null, context, List.of(), dependsOn, null, "MEDIUM", null, List.of(), null, null, null, null, null, OffsetDateTime.now(), null, null);
    }

    /** 字段残缺的草案视图（acceptance 空白或过短等），用于验证「只诊断该条、不报废整批」。 */
    private SubTaskView draftBroken(long id, String acceptance) {
        return draftFullTD9(id, "残缺条目", "内容", "交付物产物完整清单", acceptance, List.of());
    }

    /**
     * 把执行表形状的夹具映射为暂存区行并打桩 {@code listByTask}（`AD-5a-3b-2b` 落库段唯一输入）。
     *
     * <p>依赖按夹具 id → 同批 code 翻译，门 1 的图结构与旧用例保持一致。</p>
     */
    private void staged(SubTaskView... fixtures) {
        Map<Long, String> idToCode = new HashMap<>();
        for (int i = 0; i < fixtures.length; i++) {
            if (fixtures[i].id() != null) {
                idToCode.put(fixtures[i].id(), stagedCode(i + 1));
            }
        }
        List<PlanDraftView> rows = new ArrayList<>(fixtures.length);
        for (int i = 0; i < fixtures.length; i++) {
            SubTaskView v = fixtures[i];
            List<String> depCodes = v.dependsOn() == null ? List.of()
                    : v.dependsOn().stream().map(idToCode::get)
                            .filter(java.util.Objects::nonNull).toList();
            rows.add(new PlanDraftView(v.id(), TASK_ID, stagedCode(i + 1), 1, i + 1,
                    v.title(), v.content(), v.deliverable(), v.acceptance(), v.acceptanceKind(),
                    v.priority() == null ? null : com.helloai.common.constant.TaskPriority.valueOf(v.priority()),
                    v.isContract(), v.requiredSkills(), v.constraints(),
                    toUncertaintyDrafts(v.uncertainties()), depCodes, v.context(), v.updateTime()));
        }
        lenient().when(planDraftService.listByTask(TASK_ID)).thenReturn(rows);
    }

    private static String stagedCode(int seq) {
        return String.format("20261110%04d", seq);
    }

    private static List<UncertaintyDraft> toUncertaintyDrafts(List<UncertaintyView> views) {
        if (views == null) {
            return null;
        }
        List<UncertaintyDraft> drafts = new ArrayList<>(views.size());
        for (UncertaintyView u : views) {
            if (u != null) {
                drafts.add(new UncertaintyDraft(u.kind(), u.note(), u.ref(), u.blockedBy()));
            }
        }
        return drafts;
    }

    /** 暂存区行（`AD-5a-3b-2b`：草案没有执行表副本时的重生成输入）。 */
    private static PlanDraftView stagedDraftView(long id, String code, String title, String content,
                                                 String deliverable, String acceptance) {
        return new PlanDraftView(id, TASK_ID, code, 1, 1, title, content, deliverable, acceptance,
                null, null, 0, List.of(), null, List.of(), List.of(), null, OffsetDateTime.now());
    }

    /** 拆解落库段交给暂存区的草案批次（`writeDrafts` 的入参）。 */
    @SuppressWarnings("unchecked")
    private List<SubTaskDraft> savedDrafts() {
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        return captor.getValue();
    }

    /** 某条暂存区行最终落库的 context（findings 存储按 id 写回）。 */
    private Map<String, Object> findingsContext(long draftId) {
        ArgumentCaptor<PlanDraft> captor = ArgumentCaptor.forClass(PlanDraft.class);
        verify(planDraftMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        return captor.getAllValues().stream()
                .filter(row -> draftId == row.getId())
                .reduce((a, b) -> b)
                .map(PlanDraft::getContext)
                .orElseThrow();
    }

    // ══════════════════════════════════════════════════════════════
    //  幂等守卫：仅 PLANNING 任务才执行
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("任务已离开 PLANNING（超时回收/已确认）时跳过，不触碰 LLM")
    void shouldSkipWhenTaskNotPlanning() {
        TaskView task = taskView(TaskStatus.PENDING, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);

        asyncService.executeDecompose(TASK_ID);

        verify(plannerAgentPicker, never()).pickForTask(anyLong());
        verify(platformAgentExecutionService, never())
                .executeSync(anyLong(), any(AgentTask.class));
    }

    @Test
    @DisplayName("任务不存在时跳过")
    void shouldSkipWhenTaskNotFound() {
        when(taskService.getView(TASK_ID)).thenReturn(null);

        asyncService.executeDecompose(TASK_ID);

        verify(plannerAgentPicker, never()).pickForTask(anyLong());
    }

    // ══════════════════════════════════════════════════════════════
    //  正常拆解：成功落库
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("优先级继承：task.priority=HIGH，item 未给优先级 → 继承 HIGH（C4-S1）")
    void shouldInheritTaskPriority() {
        TaskView task = taskView(TaskStatus.PLANNING, "HIGH");
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [{"title":"仅标题","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        ```
                        """, "stop", "llm", 100));
        staged(draftWithPriority(11L, "HIGH"));

        asyncService.executeDecompose(TASK_ID);

        List<SubTaskDraft> saved = savedDrafts();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).priority()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("正常拆解：markdown fence 容错解析，草案写入暂存区（待确认），start/end/generated timeline 齐全")
    void shouldDecomposeAndPersistDrafts() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"设计表结构","content":"建表","deliverable":"DDL 建表脚本","acceptance":"评审通过且无语法错误","priority":"high"},
                          {"title":"实现统计接口","content":"实现接口","deliverable":"接口代码实现文件","acceptance":"接口返回 200 状态码","priority":"不合法优先级"}
                        ]
                        ```
                        """, "stop", "llm", 100));
        // saveBatch 后按 items 顺序重加载草案（防御实体 ID 未回填）；
        // mock 不落库，stub list 返回带 id 与审计上下文的"重加载结果"
        staged(draftFull(11L, "HIGH", Map.of("plannerAgentId", 9L)),
                draftFull(12L, "MEDIUM", Map.of("plannerAgentId", 9L)));

        asyncService.executeDecompose(TASK_ID);

        List<SubTaskDraft> drafts = savedDrafts();
        assertThat(drafts).hasSize(2);
        assertThat(drafts).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(SubTaskStatus.PENDING);
            assertThat(d.taskId()).isEqualTo(TASK_ID);
            assertThat(d.context()).containsEntry("plannerAgentId", 9L);
        });
        assertThat(drafts.get(0).priority()).isEqualTo("HIGH");
        // 非法优先级归一化为 MEDIUM
        assertThat(drafts.get(1).priority()).isEqualTo("MEDIUM");

        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_llm_call_start"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("B4：根层 code 序号取「任务内已用最大值 + 1」，draftSeq 仍是批内 1..N")
    void shouldStartRootSeqAfterExistingMax() {
        OffsetDateTime created = OffsetDateTime.parse("2026-11-08T10:00:00+08:00");
        when(taskService.getView(TASK_ID)).thenReturn(planningTaskAt(created));
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"设计表结构","content":"建表","deliverable":"DDL","acceptance":"运行 x 输出 y"},
                          {"title":"实现接口","content":"实现","deliverable":"代码","acceptance":"运行 a 输出 b"}
                        ]
                        """, "stop", "llm", 100));
        staged(draftFull(11L, "HIGH", null), draftFull(12L, "HIGH", null));
        // 该任务已用过根段 0001~0005（上一轮 reject 后 DISCARDED + 软删）
        when(planDraftMapper.findMaxRootSeq(TASK_ID)).thenReturn(5);

        asyncService.executeDecompose(TASK_ID);

        List<SubTaskDraft> drafts = savedDrafts();
        // 第二轮必须从 0006 起：从 0001 重起会与上一轮残留行同号 ⇒ 撞 uk_plan_draft_code
        assertThat(drafts).extracting(SubTaskDraft::draftCode)
                .containsExactly("202611080006", "202611080007");
        // draftSeq 与 code 的 seq 是两件事：前者是批内位置（dependsOn 引用它、
        // uk_sub_task_decompose_attempt 用它区分同批），**不能**跟着变成全局号
        assertThat(drafts).extracting(SubTaskDraft::draftSeq).containsExactly(1, 2);
    }

    @Test
    @DisplayName("task_plan_llm_call_end 事件携带耗时毫秒、finishReason、tokenUsage")
    void shouldRecordLlmCallEndWithObservabilityFields() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> endCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_llm_call_end"),
                eq(AgentRole.PLANNER), eq(9L), endCaptor.capture());
        assertThat(endCaptor.getValue())
                .containsKeys("costMs", "finishReason", "tokenUsage", "success")
                .containsEntry("finishReason", "stop")
                .containsEntry("tokenUsage", 100)
                .containsEntry("success", true);
    }

    // ══════════════════════════════════════════════════════════════
    //  G-004 增量 B：任务技能要求注入拆解 Prompt（真实流量行使收口）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-004 增量 B：任务声明技能 → 拆解 Prompt 注入技能清单与对齐要求（占位符实渲染）")
    void shouldInjectRequiredSkillsIntoDecomposePrompt() {
        TaskView task = new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null,
                null, null, TaskStatus.PLANNING, null, null, null,
                List.of("eng-doc-standard", "eng-verification"), null, null, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"文档产出","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();
        // 声明序逗号拼接 + 技能对齐要求（与执行侧注入、审查侧核验同一清单）
        assertThat(prompt)
                .contains("任务技能要求：eng-doc-standard, eng-verification")
                .contains("技能对齐")
                .doesNotContain("{{TASK_REQUIRED_SKILLS}}");
    }

    @Test
    @DisplayName("G-004 增量 B：任务未声明技能 → 占位符降级文案，无标签泄漏（行为零变化）")
    void shouldUseFallbackWordingWhenNoRequiredSkills() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"普通执行","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();
        assertThat(prompt).contains("任务技能要求：（任务未声明技能要求）");
        // 占位符已完全渲染无残留（模板第 7 条示例含技能名是固定模板文案，非泄漏；
        // 降级语义 = 占位符被降级文案替换且无未渲染残留）
        assertThat(prompt).doesNotContain("{{TASK_REQUIRED_SKILLS}}");
    }

    // ══════════════════════════════════════════════════════════════
    //  执行环境段（{{EXECUTION_ENV}}，2026-11-12）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("执行环境：未声明 → 注入显式标记（不是空段）+ 占位符无残留")
    void shouldInjectUndeclaredMarkerWhenNoExecutionEnv() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"普通执行","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        assertThat(captor.getValue().getUserPrompt())
                // 空段会让模型既不申报也不追问；标记才是可判定信号
                .contains("未声明执行环境")
                .doesNotContain("{{EXECUTION_ENV}}");
    }

    @Test
    @DisplayName("执行环境：已声明 → 路径 / 基线 / 数据源原样进提示词（模型据此照抄，不再写「仓库中」）")
    void shouldInjectDeclaredExecutionEnv() {
        Map<String, Object> context = Map.of(TaskContextKeys.EXECUTION_ENV, Map.of(
                ExecutionEnv.KEY_REPO, Map.of("kind", "local", "value", "E:/yhzx/1027/helloai", "baseRef", "main"),
                ExecutionEnv.KEY_RUNTIME, List.of("jdk17", "bash"),
                ExecutionEnv.KEY_DATASOURCES, List.of(
                        Map.of("type", "postgresql", "url", "localhost:15432/helloai", "ready", true))));
        TaskView task = new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                TaskStatus.PLANNING, null, null, context, null, null, null, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"普通执行","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        assertThat(captor.getValue().getUserPrompt())
                .contains("E:/yhzx/1027/helloai")
                .contains("main")
                .contains("jdk17")
                .contains("postgresql")
                .contains("已就绪")
                .doesNotContain("未声明执行环境")
                .doesNotContain("{{EXECUTION_ENV}}");
    }

    // ══════════════════════════════════════════════════════════════
    //  G-010 能力感知：子任务级技能目录过滤 + constraints 落库
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-010：requiredSkills 目录过滤——命中保留、幻觉丢弃并审计；constraints 直接落库")
    void shouldFilterRequiredSkillsAndPersistConstraints() {
        when(agentSkillSpecService.listPackages()).thenReturn(List.of(
                new SkillPackage("eng-doc-standard", "1.0.0", "文档规范",
                        List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), "eng-doc-standard.md")));
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"文档产出","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                           "requiredSkills":["eng-doc-standard","幻觉技能"],"constraints":"不得改对外接口"}
                        ]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        SubTaskDraft saved = captor.getValue().get(0);
        // 目录命中保留、幻觉标签丢弃
        assertThat(saved.requiredSkills()).containsExactly("eng-doc-standard");
        assertThat(saved.constraints()).isEqualTo("不得改对外接口");
        // 幻觉标签被过滤并记审计
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_skill_filtered"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("G-010 §5 防御：技能目录超 20 项截断至前 20 并提示，不泄漏未展示项")
    void shouldTruncateSkillCatalogOver20() {
        List<SkillPackage> many = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            many.add(new SkillPackage("skill-" + i, "1.0.0", "描述" + i,
                    List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), "skill-" + i + ".md"));
        }
        when(agentSkillSpecService.listPackages()).thenReturn(many);
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        String prompt = captor.getValue().getUserPrompt();
        assertThat(prompt).contains("skill-1 v1.0.0")
                .contains("skill-20 v1.0.0")
                .contains("仅展示前 20 项");
        assertThat(prompt).doesNotContain("skill-21");
    }

    // ══════════════════════════════════════════════════════════════
    //  契约先行拆解：contract 字段解析与落库
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("contract=true 落库 isContract=1；false/缺省/字符串布尔宽容解析降级为 0")
    void shouldParseContractFlagToIsContract() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"契约定义","content":"接口签名","deliverable":"契约文档完整版本","acceptance":"下游可照做且无歧义之处","contract":true,"dependsOn":[]},
                          {"title":"下游实现","content":"照契约实现","deliverable":"实现代码完整版本","acceptance":"契约用例全部通过验收","contract":false,"dependsOn":[1]},
                          {"title":"普通子任务","content":"缺省 contract","deliverable":"交付物产物完整清单","acceptance":"验证点全部通过确认完成","dependsOn":[1]},
                          {"title":"字符串布尔","content":"contract 给字符串","deliverable":"交付物产物完整清单","acceptance":"验证点全部通过确认完成","contract":"true","dependsOn":[1]}
                        ]
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L), draft(13L), draft(14L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        List<SubTaskDraft> drafts = captor.getValue();
        assertThat(drafts).hasSize(4);
        // 布尔 true / 字符串 "true" → 1；false/缺省 → 0（Boolean.TRUE.equals 语义降级）
        assertThat(drafts).extracting(SubTaskDraft::isContract)
                .containsExactly(1, 0, 0, 1);
    }

    @Test
    @DisplayName("contract 完全非法值（非布尔）：整批解析失败回退 PENDING，不落库（可重拆恢复）")
    void shouldRollbackWhenContractFlagIsInvalid() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"契约定义","content":"接口签名","contract":"yes","dependsOn":[]},
                          {"title":"下游实现","dependsOn":[1]}
                        ]
                        """, "stop", "llm", 100));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService, never()).writeDrafts(anyLong(), anyString(), any(), any());
        verify(taskService).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  失败路径：内部闭环回退 PENDING（不再抛出）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2026-11-08 口径：草案 acceptance 空白 → 照常落库 + 该条带 G2_BLANK，任务留 PLANNING 待修正")
    void shouldPersistDraftsWithBlockingFindingWhenAcceptanceBlank() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"完整条目","content":"内容","deliverable":"交付物产物完整清单","acceptance":"运行 X 命令输出预期 Y"},
                          {"title":"残缺条目","content":"内容","deliverable":"交付物产物完整清单","acceptance":"   "}
                        ]
                        """, "stop", "llm", 100));
        // 落库后重读：第 2 条 acceptance 仍是空白（模型就这么输出的）
        staged(draft(11L), draftBroken(12L, "   "));

        asyncService.executeDecompose(TASK_ID);

        // 不报废整批：草案照常写入暂存区（待确认草案不在执行表，不参与 claim/派发），任务留在 PLANNING
        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"), eq(AgentRole.PLANNER), isNull(), anyMap());
        // 结论落在命中的草案上；未命中的草案写清零 payload（写与清同源）
        assertThat(String.valueOf(findingsContext(12L).get(SubTaskContextKeys.GATE_FINDINGS)))
                .contains(DraftGateChecker.G2_BLANK);
        Object clearGate = findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS);
        assertThat(clearGate).isInstanceOfSatisfying(Map.class,
                m -> assertThat(m.get("findings")).isInstanceOfSatisfying(List.class, List::isEmpty));
        // 阻断仍留痕（排障只需要这一条：谁不合格、为什么）
        verify(taskTimelineService).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("2026-11-08 口径：门 1 成环 → 草案落库 + G1_CYCLE 挂首条，不再落库前整批报废")
    void shouldPersistDraftsWithCycleFinding() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"甲","content":"内容甲","deliverable":"交付物甲完整清单","acceptance":"验收判据甲说明文字","dependsOn":[2]},
                          {"title":"乙","content":"内容乙","deliverable":"交付物乙完整清单","acceptance":"验收判据乙说明文字","dependsOn":[1]}
                        ]
                        """, "stop", "llm", 100));
        SubTaskView a = draftFullTD9(11L, "甲", "内容甲", "交付物甲完整清单", "运行 pytest 输出 5 passed", List.of(12L));
        SubTaskView b = draftFullTD9(12L, "乙", "内容乙", "交付物乙完整清单", "运行 pytest 输出 5 passed", List.of(11L));
        staged(a, b);

        asyncService.executeDecompose(TASK_ID);

        // 草案落库、任务不回退：环是「计划需要修」而不是「这一批拿不到」
        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        // 环是批次级问题（无单条主体）⇒ 确定性地挂到首条草案
        assertThat(String.valueOf(findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS)))
                .contains(DraftGateChecker.G1_CYCLE);
        verify(taskTimelineService).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"),
                eq(AgentRole.PLANNER), eq(9L),
                argThat(payload -> "decompose".equals(payload.get("phase"))
                        && String.valueOf(payload.get("items")).contains(DraftGateChecker.G1_CYCLE)));
    }

    @Test
    @DisplayName("TD-13 S4：门 2 合规（deliverable 带书名号但 acceptance 有断言）→ 无 gate_warned、无阻断")
    void shouldNotEmitGateFindingsWhenDraftHasQuotedTermNotRepeatedInAcceptance() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"甲","content":"内容甲","deliverable":"输出《巡检台账》契约文档","acceptance":"运行 X 命令输出预期 Y"},
                          {"title":"乙","content":"内容乙","deliverable":"输出《巡检报表》统计脚本","acceptance":"接口 Z 返回 200 状态码"}
                        ]
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        // 门禁结论不再写进 SubTaskDraft.context：它挂在暂存区行的 context 上
        assertThat(savedDrafts()).allSatisfy(
                d -> assertThat(d.context()).doesNotContainKey(SubTaskContextKeys.GATE_FINDINGS));
        verify(taskTimelineService, never()).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_warned"), any(), any(), anyMap());
        verify(taskTimelineService, never()).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("P1 必填兜底：四字段齐全（含边界空白裁剪后非空）→ 正常落库，不记审计")
    void shouldNotFireFieldAuditWhenRequiredFieldsPresent() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"条目一","content":"内容一","deliverable":"交付物一完整产出","acceptance":"运行 X 命令输出预期 Y"},
                          {"title":"条目二","content":"内容二","deliverable":"交付物二完整产出","acceptance":"接口 Z 返回 200 状态码"}
                        ]
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_draft_field_missing"),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("JSON 解析失败：回退 PENDING 并记录 task_plan_failed，不落库")
    void shouldRollbackWhenLlmOutputIsNotJson() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("抱歉，我无法完成拆解。", "stop", "llm", 10));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService, never()).writeDrafts(anyLong(), anyString(), any(), any());
        // 失败回退走 CAS（lambdaUpdate）
        verify(taskService).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("LLM 调用失败：回退 PENDING 并记录 task_plan_failed")
    void shouldRollbackWhenLlmCallFails() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.failure("provider timeout", "error", "llm"));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService, never()).writeDrafts(anyLong(), anyString(), any(), any());
        verify(taskService).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("选型器无可用 Planner 时：回退 PENDING 并记录 task_plan_failed")
    void shouldRollbackWhenNoPlatformPlannerAgent() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenThrow(new BizException(
                "无可用的平台内 Planner Agent（需要 role=PLANNER 且 accessType=API_KEY_LLM）；"
                        + "请先在 Agent 管理中注册，或改用外部 Planner Agent 手工创建子任务"));

        asyncService.executeDecompose(TASK_ID);

        verify(taskService).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  dependsOn 越界 / 回写
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("依赖序号越界 → 丢边 + 该条带 G1_DEP_OUT_OF_RANGE，草案照常落库")
    void shouldRejectWhenDependsOnPointsToMissingDraft() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 第 2 条依赖序号 5，本批只有 2 条 ⇒ 越界
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"第一步","content":"准备","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","dependsOn":[]},
                          {"title":"第二步","content":"执行","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","dependsOn":[5]}
                        ]
                        ```
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        // 越界边不落暂存区（edits 为空时 applyDependencies 收到空 map 并直接返回）
        verify(planDraftService).applyDependencies(eq(TASK_ID), argThat(Map::isEmpty));
        // 但草案仍落库：问题以 block finding 的形式挂在第 2 条草案上（用户可先修依赖再确认）
        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        assertThat(String.valueOf(findingsContext(12L).get(SubTaskContextKeys.GATE_FINDINGS)))
                .contains(DraftGateChecker.G1_DEP_OUT_OF_RANGE);
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
    }

    @Test
    @DisplayName("依赖回写：序号 → 同批 code 写入暂存区 depends_on_codes")
    void shouldApplyDependsOnWhenAllTargetsExist() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"第一步","content":"准备","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","dependsOn":[]},
                          {"title":"第二步","content":"执行","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","dependsOn":[1]}
                        ]
                        ```
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        // 序号 1 → 第 1 条的 code，回写第 2 条草案
        verify(planDraftService).applyDependencies(TASK_ID,
                Map.of("202611100002", List.of("202611100001")));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  G-011 需求包渲染：{{REQUIREMENT_PACKAGE}} 占位符
    // ══════════════════════════════════════════════════════════════

    private TaskView taskWithRequirementPackage(Map<String, Object> pkgFields) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                TaskStatus.PLANNING, null, null,
                Map.of(RequirementPackageParser.CONTEXT_KEY_REQUIREMENT_PACKAGE, pkgFields), null, null, null, null);
    }

    /** COARSE 粒度用例：白名单内全部 CLI_CLIENT（外部强执行者）执行者。 */
    private AgentProfileSnapshot cliExecutor() {
        return AgentProfileSnapshot.builder()
                .id(1L)
                .accessType(AgentAccessType.CLI_CLIENT)
                .build();
    }

    private String captureUserPrompt() {
        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(anyLong(), captor.capture());
        return captor.getValue().getUserPrompt();
    }

    @Test
    @DisplayName("G-011：任务带需求包 → Prompt 渲染六字段列表（含 P1-1 任务级验收标准），占位符无残留")
    void shouldRenderRequirementPackageIntoPrompt() {
        TaskView task = taskWithRequirementPackage(Map.of(
                "goal", "每日自动出日报",
                "scope", List.of("报表生成", "定时调度"),
                "outOfScope", List.of("不做 UI 改造"),
                "acceptanceCriteria", List.of("每日 08:00 前产出日报且字段齐全"),
                "assumptions", List.of("数据源可达且口径与昨日一致"),
                "openQuestions", List.of("接口是否有存量调用方未确认")));
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        String prompt = captureUserPrompt();
        assertThat(prompt)
                .contains("- 目标：每日自动出日报")
                .contains("- 范围：")
                .contains("报表生成")
                .contains("明确不做（outOfScope）")
                .contains("不做 UI 改造")
                .contains("任务级验收标准（acceptanceCriteria）")
                .contains("每日 08:00 前产出日报且字段齐全")
                .contains("关键假设（推断项，须标注）")
                .contains("数据源可达且口径与昨日一致")
                .contains("待确认事项（openQuestions）")
                .contains("接口是否有存量调用方未确认");
        assertThat(prompt).doesNotContain("{{REQUIREMENT_PACKAGE}}");
    }

    @Test
    @DisplayName("G-011：任务无需求包（未走澄清链路）→ 占位文案渲染，行为零变化")
    void shouldRenderPlaceholderWhenNoRequirementPackage() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        assertThat(captureUserPrompt())
                .contains("（本任务未经过澄清链路，无结构化需求包——按任务描述拆解）")
                .doesNotContain("{{REQUIREMENT_PACKAGE}}");
    }

    // ══════════════════════════════════════════════════════════════
    //  G-011 uncertainties：落库 / kind 降级审计 / 非法形态防御
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-011：uncertainties 落库——ASSUMPTION/UNCONFIRMED 原样保留")
    void shouldPersistUncertainties() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "uncertainties":[
                            {"kind":"ASSUMPTION","note":"数据源可达"},
                            {"kind":"UNCONFIRMED","note":"接口是否有存量调用方未确认"}
                          ]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        List<UncertaintyDraft> uncertainties = captor.getValue().get(0).uncertainties();
        assertThat(uncertainties).hasSize(2)
                .extracting(UncertaintyDraft::kind)
                .containsExactly(UncertaintyDraft.KIND_ASSUMPTION, UncertaintyDraft.KIND_UNCONFIRMED);
        assertThat(uncertainties).extracting(UncertaintyDraft::note)
                .containsExactly("数据源可达", "接口是否有存量调用方未确认");
    }

    @Test
    @DisplayName("TD-11：验收要求取回 URL 证据 × 缺口 UNCONFIRMED → 只标注不阻断（写 context + WARN 事件）")
    void shouldAnnotateAcceptancePreconditionsWithoutBlocking() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"竞品证据台账","content":"三家竞品 A/B/C 各采集公开功能与定价，未确认对应真实产品时先用代号建档，不要自行替换",
                          "deliverable":"证据台账表（每条含来源 URL 与原文摘录）",
                          "acceptance":"STRUCTURAL：证据条目总数 ≥15 条，每条附来源 URL 且页面内容与原文摘录一致",
                          "uncertainties":[{"kind":"UNCONFIRMED","note":"A/B/C 对应哪三款真实产品待业务方拍板"}]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        // 只标注：acceptance / acceptanceKind 一字未改，草案照常落库
        SubTaskDraft saved = captor.getValue().get(0);
        assertThat(saved.context().get(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS))
                .isEqualTo(List.of("U1"));
        assertThat(saved.acceptance()).contains("≥15 条");
        assertThat(saved.uncertainties()).extracting(UncertaintyDraft::ref).containsExactly("U1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_acceptance_precondition_unmet"),
                eq(AgentRole.PLANNER), eq(9L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("draftSeq", 1)
                .containsEntry("title", "竞品证据台账")
                .containsEntry("preconditionRefs", List.of("U1"));
    }

    @Test
    @DisplayName("TD-11 反例：验收只含结构量词 → context 不带 acceptancePreconditions、无事件")
    void shouldNotAnnotatePureStructuralAcceptance() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单",
                          "acceptance":"STRUCTURAL：输出 5 个章节，每章 ≥1 条结论",
                          "uncertainties":[{"kind":"UNCONFIRMED","note":"接口是否有存量调用方未确认"}]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        assertThat(captor.getValue().get(0).context())
                .doesNotContainKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_acceptance_precondition_unmet"),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("TD-13 S1：UNCONFIRMED 申报在别的草案 → 本条不标注（跨草案拼接已删除）")
    void shouldNotAnnotateFromOtherDraftsUncertainties() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"竞品 A 事实采集","content":"采集公开功能与定价",
                          "deliverable":"证据台账表（每条含来源 URL）",
                          "acceptance":"STRUCTURAL：证据条目 ≥15 条，每条附来源 URL",
                          "uncertainties":[{"kind":"TO_LOOK_UP","note":"竞品 A 定价页需联网查"}]},
                         {"title":"代号映射确认","content":"确认 A/B/C 对应哪三款真实产品",
                          "deliverable":"代号映射确认记录表（含 3 行）",
                          "acceptance":"STRUCTURAL：输出 1 份映射确认表，含 3 行记录",
                          "uncertainties":[{"kind":"UNCONFIRMED","note":"A/B/C 对应哪三款真实产品待业务方拍板"}]}]
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).context())
                .doesNotContainKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
        assertThat(captor.getValue().get(1).context())
                .doesNotContainKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_acceptance_precondition_unmet"),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("TD-13 S2：缺口申报跨草案影响面 → blockedBy 原样落库（3 条其它草案）")
    void shouldPersistCrossDraftBlockedBy() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"代号映射确认","content":"确认 A/B/C 对应哪三款真实产品",
                          "deliverable":"代号映射确认记录表（含 3 行）",
                          "acceptance":"STRUCTURAL：输出 1 份映射确认表，含 3 行记录",
                          "uncertainties":[{"kind":"UNCONFIRMED","note":"A/B/C 对应哪三款真实产品待业务方拍板",
                                            "ref":"U1","blockedBy":[2,3,4]}]},
                         {"title":"竞品 A 事实采集","content":"采集公开功能与定价",
                          "deliverable":"证据台账表 A（每条含来源 URL）",
                          "acceptance":"STRUCTURAL：证据条目 ≥4 条，每条附来源 URL"},
                         {"title":"竞品 B 事实采集","content":"采集公开功能与定价",
                          "deliverable":"证据台账表 B（每条含来源 URL）",
                          "acceptance":"STRUCTURAL：证据条目 ≥4 条，每条附来源 URL"},
                         {"title":"竞品 C 事实采集","content":"采集公开功能与定价",
                          "deliverable":"证据台账表 C（每条含来源 URL）",
                          "acceptance":"STRUCTURAL：证据条目 ≥4 条，每条附来源 URL"}]
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L), draft(13L), draft(14L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        List<UncertaintyDraft> uncertainties = captor.getValue().get(0).uncertainties();
        assertThat(uncertainties).hasSize(1);
        // 断点① 的证据：同一缺口能一次表达「卡住 2/3/4 三条彼此无依赖的草案」
        assertThat(uncertainties.get(0).blockedBy()).containsExactly(2, 3, 4);
        // 未申报影响面的条目必须保持 null（与「申报为空」语义不同，不得被归一成 []）
        assertThat(captor.getValue().get(1).uncertainties()).isEmpty();
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_uncertainty_blocked_by_normalized"),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("TD-13 S2：blockedBy 自引用 / 越界 / 重复被丢弃并聚合审计")
    void shouldNormalizeInvalidBlockedBy() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"代号映射确认","content":"确认 A/B/C 对应哪三款真实产品",
                          "deliverable":"代号映射确认记录表（含 3 行）",
                          "acceptance":"STRUCTURAL：输出 1 份映射确认表，含 3 行记录",
                          "uncertainties":[
                            {"kind":"UNCONFIRMED","note":"A/B/C 映射未确认","ref":"U1","blockedBy":[1,2,2,9,0]},
                            {"kind":"ASSUMPTION","note":"假定内部数据口径不变","ref":"U2"}
                          ]},
                         {"title":"证据台账","content":"采集公开事实",
                          "deliverable":"证据台账表（每条含来源 URL）",
                          "acceptance":"STRUCTURAL：证据条目 ≥15 条，每条附来源 URL"}]
                        """, "stop", "llm", 100));
        staged(draft(11L), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        List<UncertaintyDraft> uncertainties = captor.getValue().get(0).uncertainties();
        // 1 = 自引用（本草案序号），9 / 0 = 越界，重复的 2 只留一个
        assertThat(uncertainties.get(0).blockedBy()).containsExactly(2);
        assertThat(uncertainties.get(1).blockedBy()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_uncertainty_blocked_by_normalized"),
                eq(AgentRole.PLANNER), eq(9L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("draftSeq", 1)
                .containsEntry("ref", "U1")
                .containsEntry("keptBlockedBy", List.of(2));
        assertThat((List<?>) payloadCaptor.getValue().get("dropped")).hasSize(4);
    }

    @Test
    @DisplayName("G-011 D3：非法 kind 降级 UNCONFIRMED 并审计（不丢弃）；空白 note 丢弃")
    void shouldDegradeIllegalKindAndDropBlankNote() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "uncertainties":[
                            {"kind":"MAYBE","note":"猜测性推断"},
                            {"kind":"UNCONFIRMED","note":"   "},
                            {"kind":"UNCONFIRMED","note":"正常缺口"}
                          ]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        List<UncertaintyDraft> uncertainties = captor.getValue().get(0).uncertainties();
        // 非法 kind 降级不丢弃；空白 note 条目丢弃
        assertThat(uncertainties).hasSize(2);
        assertThat(uncertainties.get(0).kind()).isEqualTo(UncertaintyDraft.KIND_UNCONFIRMED);
        assertThat(uncertainties.get(0).note()).isEqualTo("猜测性推断");
        assertThat(uncertainties.get(1).kind()).isEqualTo(UncertaintyDraft.KIND_UNCONFIRMED);
        assertThat(uncertainties.get(1).note()).isEqualTo("正常缺口");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_uncertainty_degraded"),
                eq(AgentRole.PLANNER), eq(9L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("rawKind", "MAYBE")
                .containsEntry("degradedTo", UncertaintyDraft.KIND_UNCONFIRMED);
    }

    @Test
    @DisplayName("G-011 防御：uncertainties 非数组形态 → 回落空列表，不阻断拆解")
    void shouldFallbackToEmptyWhenUncertaintiesNotArray() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","uncertainties":"非法形态"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(planDraftRoundService).writeDrafts(eq(TASK_ID), eq("attempt-test"),
                nullable(OffsetDateTime.class), captor.capture());
        assertThat(captor.getValue().get(0).uncertainties()).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    //  G-010 缺口④清偿：COARSE constraints 缺失 WARN（不阻断落库）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-010 缺口④：COARSE 粒度 constraints 缺失 → task_plan_constraints_missing WARN")
    void shouldWarnWhenCoarseConstraintsMissing() {
        TaskView task = taskWithPolicy(TaskAgentPolicy.build(null, List.of(1L), null, null, null));
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(agentService.listProfilesByIds(List.of(1L))).thenReturn(List.of(cliExecutor()));
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"只拆目标","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_constraints_missing"),
                eq(AgentRole.PLANNER), eq(9L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("draftSeq", 1)
                .containsEntry("title", "只拆目标");
    }

    @Test
    @DisplayName("G-010：COARSE 粒度 constraints 齐全 → 不记 WARN")
    void shouldNotWarnWhenCoarseConstraintsPresent() {
        TaskView task = taskWithPolicy(TaskAgentPolicy.build(null, List.of(1L), null, null, null));
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(agentService.listProfilesByIds(List.of(1L))).thenReturn(List.of(cliExecutor()));
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"只拆目标","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "constraints":"不得改线上配置"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_constraints_missing"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  G-011 D5 兜底审计：openQuestions 无 UNCONFIRMED 继承 → WARN
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-011 D5：openQuestions 非空但拆解产物无 UNCONFIRMED → task_plan_uncertainty_missing WARN")
    void shouldWarnWhenOpenQuestionsNotInherited() {
        TaskView task = taskWithRequirementPackage(Map.of(
                "openQuestions", List.of("接口是否有存量调用方未确认")));
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // LLM 只申报 ASSUMPTION（推断），未按继承规则转出 UNCONFIRMED → 兜底 WARN
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "uncertainties":[{"kind":"ASSUMPTION","note":"数据源可达"}]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_uncertainty_missing"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("G-011 D5：拆解产物含 UNCONFIRMED 继承 → 不记 WARN")
    void shouldNotWarnWhenOpenQuestionsInherited() {
        TaskView task = taskWithRequirementPackage(Map.of(
                "openQuestions", List.of("接口是否有存量调用方未确认")));
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "uncertainties":[{"kind":"UNCONFIRMED","note":"接口是否有存量调用方未确认"}]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_uncertainty_missing"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("G-011 D5：无需求包（openQuestions 空）→ 不记 WARN，行为零变化")
    void shouldNotWarnWhenNoRequirementPackage() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_uncertainty_missing"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  G-022 拆解侧前置校验：声明的技能须有在岗 Agent 可承接（告警而非阻断）
    // ══════════════════════════════════════════════════════════════

    private SkillPackage engDocStandard() {
        return new SkillPackage("eng-doc-standard", "1.0.0", "文档规范",
                List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), "eng-doc-standard.md");
    }

    @Test
    @DisplayName("G-022 拆解侧前置校验：声明的技能无任何在岗 Agent 具备 → task_plan_skill_unmatched 告警，草案照常落库")
    void shouldWarnWhenNoActiveAgentCoversRequiredSkills() {
        when(agentSkillSpecService.listPackages()).thenReturn(List.of(engDocStandard()));
        // 在岗 Agent 只具备 thinking，无 eng-doc-standard ⇒ 无承接者
        when(agentService.listActiveSkillSets()).thenReturn(List.of(List.of("thinking")));
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"文档产出","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "requiredSkills":["eng-doc-standard"]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        // 告警而非阻断：草案仍落库
        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_skill_unmatched"),
                eq(AgentRole.PLANNER), eq(9L), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("draftSeq", 1)
                .containsEntry("title", "文档产出")
                .containsEntry("requiredSkills", List.of("eng-doc-standard"))
                .containsEntry("activeAgentCount", 1);
    }

    @Test
    @DisplayName("G-022 拆解侧前置校验：存在可承接的在岗 Agent → 不记告警")
    void shouldNotWarnWhenActiveAgentCoversRequiredSkills() {
        when(agentSkillSpecService.listPackages()).thenReturn(List.of(engDocStandard()));
        // 在岗 Agent 具备 eng-doc-standard ⇒ 有承接者
        when(agentService.listActiveSkillSets()).thenReturn(List.of(List.of("eng-doc-standard")));
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"文档产出","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容",
                          "requiredSkills":["eng-doc-standard"]}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_skill_unmatched"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  TD-5 接线：acceptanceKind 显式声明判定 + 分流校验
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TD-5 接线：acceptanceKind 缺失（回落 AUTOMATED）且文本普通 → Warn 挂草案，不阻断落库")
    void shouldWarnAndPersistWhenKindMissing() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // acceptanceKind 字段缺失 → parse 回落 AUTOMATED；文本无命令/断言 → 应走 Warn 而非 Fail
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"设计表结构","content":"建表","deliverable":"DDL 建表脚本","acceptance":"评审通过且无语法错误"},
                         {"title":"实现统计接口","content":"实现","deliverable":"接口代码实现文件","acceptance":"接口返回 200 状态码"}]
                        """, "stop", "llm", 100));
        // 落库后重读：第 1 条文本无命令/断言（Warn），第 2 条合规
        staged(draftWithKind(11L, "评审通过且无语法错误", null, List.of()), draft(12L));

        asyncService.executeDecompose(TASK_ID);

        // 不阻断：草案仍落库
        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        // Warn 结论挂在命中的那条草案上
        assertThat(String.valueOf(findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS)))
                .contains(DraftGateChecker.G2_KIND_FORM_CONFLICT);
        // 只有 warn 没有 block ⇒ 记 warned 事件、不记 blocked
        verify(taskTimelineService).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_warned"),
                eq(AgentRole.PLANNER), eq(9L),
                argThat(payload -> Integer.valueOf(1).equals(payload.get("warnCount"))));
        verify(taskTimelineService, never()).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("TD-5 接线：显式声明 AUTOMATED 但验收是空话 → 该条带 G2_KIND_FORM_CONFLICT（不整批作废）")
    void shouldRejectWhenExplicitAutomatedMismatch() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 显式 AUTOMATED + 空话「质量良好」→ Fail
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"写文档","content":"c","deliverable":"d","acceptance":"质量良好且表达清晰通顺","acceptanceKind":"AUTOMATED"}]
                        """, "stop", "llm", 100));
        // 「是否显式声明」靠 context 里的原文，不能靠落库枚举（parse 会把未声明回落成 AUTOMATED）
        staged(draftWithKind(11L, "质量良好且表达清晰通顺", "AUTOMATED", List.of()));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        assertThat(String.valueOf(findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS)))
                .contains(DraftGateChecker.G2_KIND_FORM_CONFLICT);
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
    }

    @Test
    @DisplayName("TD-5 接线：显式声明 STRUCTURAL + 可数量词 → 通过，草案落库且无 findings")
    void shouldPassWhenExplicitStructuralCountable() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"产出报告","content":"c","deliverable":"研究报告成品文档","acceptance":"不少于 5 章完整内容","acceptanceKind":"STRUCTURAL"}]
                        """, "stop", "llm", 100));
        staged(draftWithKind(11L, "不少于 5 章完整内容", "STRUCTURAL", List.of()));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        // 合规 ⇒ 写的是清零 payload（同一入口同时承担「清上一轮结论」）
        Object clearGate = findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS);
        assertThat(clearGate).isInstanceOfSatisfying(Map.class,
                m -> assertThat(m.get("findings")).isInstanceOfSatisfying(List.class, List::isEmpty));
        verify(taskTimelineService, never()).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("TD-5 长度闸：acceptance 低于字符下限 → 该条带 G2_LEN_MIN（不整批作废）")
    void shouldRejectWhenAcceptanceBelowFloor() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 各字段非空（过空白校验）但 acceptance=4 字符 < 10 下限 → 长度闸命中
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"产出物","content":"c","deliverable":"报告","acceptance":"通过即可"}]
                        """, "stop", "llm", 100));
        staged(draftBroken(11L, "通过即可"));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        assertThat(String.valueOf(findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS)))
                .contains(DraftGateChecker.G2_LEN_MIN);
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
    }

    @Test
    @DisplayName("P1-C：deliverable 极短但不为空 → 正常落库（`三行状态回执` 存量合法形态）")
    void shouldSaveWhenDeliverableIsShortButNotBlank() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"接口自检","content":"c","deliverable":"三行状态回执","acceptance":"三项可数断言：① 正文包含 API-CHECK-DONE ② getMySkill=200 ③ list=200"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("TD-5 长度闸：acceptance/deliverable 均达到下限 → 正常落库")
    void shouldPassWhenFieldLengthMeetsFloor() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"产出文档","content":"c","deliverable":"文档成品交付完整版","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
    }

    // ══════════════════════════════════════════════════════════════
    //  `TD-9` 单条草案 regenerate
    //  ══════════════════════════════════════════════════════════════

    private static final Long ORIGINAL_DRAFT_ID = 55L;

    /** `TD-9` 单条草案视图（四要素 + 优先级 + 依赖齐全，待确认；createTime 非 null）。 */
    private SubTaskView draftFullTD9(long id, String title, String content, String deliverable,
                                     String acceptance, List<Long> dependsOn) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, title, content, deliverable, acceptance, null, null, null, List.of(), dependsOn, null, "MEDIUM", null, List.of(), null, null, null, null, null, OffsetDateTime.now(), null, null);
    }

    /** 带位置身份的草案（27 参 canonical；`TD-16d-2` regenerate 暂存区同步用例用）。 */
    private SubTaskView draftWithCodeTD9(long id, String title, String content, String deliverable,
                                         String acceptance, List<Long> dependsOn, String code) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, title, content, deliverable, acceptance, null, null, null, List.of(), dependsOn, null, "MEDIUM", null, List.of(), null, null, null, null, null, null, null, code);
    }

    @Test
    @DisplayName("深度分档表：四个原型按设计取值，DATA/OPERATE 与 null 走显式 default")
    void shouldMapDepthLimitByArchetype() {
        var limits = plannerDecomposeProperties.getMaxExpandDepth();
        assertThat(limits.limitOf(com.helloai.common.constant.TaskArchetype.BUILD)).isEqualTo(2);
        assertThat(limits.limitOf(com.helloai.common.constant.TaskArchetype.DOCUMENT)).isEqualTo(2);
        assertThat(limits.limitOf(com.helloai.common.constant.TaskArchetype.RESEARCH)).isEqualTo(2);
        assertThat(limits.limitOf(com.helloai.common.constant.TaskArchetype.DECIDE)).isEqualTo(1);
        assertThat(limits.limitOf(com.helloai.common.constant.TaskArchetype.DATA)).isEqualTo(2);
        assertThat(limits.limitOf(com.helloai.common.constant.TaskArchetype.OPERATE)).isEqualTo(2);
        // null 与上游 TaskArchetype.parse 同口径：回落 BUILD（AD-6 起上限 2），不是回落 default
        assertThat(limits.limitOf(null)).isEqualTo(2);
    }

    @Test
    @DisplayName("AD-5a-3b-2b：暂存区草案重生成——四要素直接写暂存区，时间线不带伪造 subTaskId")
    void shouldRegenerateStagedDraft() {
        PlanDraftView staged = stagedDraftView(9L, "202611080003", "原标题", "原内容",
                "原交付物产物", "原验收判据内容");
        PlanDraftView updated = stagedDraftView(9L, "202611080003", "原标题", "新内容",
                "新交付物产物完整清单", "不少于 5 章完整内容且章节编号连续");
        when(planDraftService.getByCode(TASK_ID, "202611080003")).thenReturn(staged, updated);
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("""
                        {"title":"LLM 想改标题","content":"新内容","deliverable":"新交付物产物完整清单",
                         "acceptance":"不少于 5 章完整内容且章节编号连续","acceptanceKind":"STRUCTURAL"}
                        """, "stop", "llm", 100));
        when(agentSkillSpecService.listPackages()).thenReturn(List.of());

        SubTaskView result = asyncService.regenerateStaged(TASK_ID, "202611080003");

        verify(planDraftService).updateByCode(TASK_ID, "202611080003", "新内容",
                "新交付物产物完整清单", "不少于 5 章完整内容且章节编号连续",
                null, null, null, "STRUCTURAL");
        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.title()).isEqualTo("原标题");
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_plan_draft_regenerated"), eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  s4-5：「阻断必留痕」补齐到另外两个阻断点 + 成功路径清 gateFindings
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s4-5 成功拆解：清空各草案残留的 gateFindings（写空列表 + clearedAt）")
    void shouldClearGateFindingsAfterSuccessfulDecompose() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"产出报告","content":"c","deliverable":"研究报告成品文档",
                          "acceptance":"不少于 5 章完整内容","acceptanceKind":"STRUCTURAL"}]
                        """, "stop", "llm", 100));
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
        // 成功路径必须清掉历史阻断记录，否则确认侧会把上一轮的 findings 当成「当前未解」
        Object clearGate = findingsContext(11L).get(SubTaskContextKeys.GATE_FINDINGS);
        assertThat(clearGate).isInstanceOfSatisfying(Map.class, m -> {
            assertThat(m.get("findings")).isInstanceOfSatisfying(List.class, List::isEmpty);
            assertThat(m.get("clearedAt")).isNotNull();
        });
        // 成功路径不得产生阻断审计
        verify(taskTimelineService, never()).recordEventInNewTx(
                eq(TASK_ID), isNull(), eq("task_plan_gate_blocked"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  V146：租约 / 幂等键 / 状态推进分流
    // ══════════════════════════════════════════════════════════════

    /** 一份能过门禁的最小 LLM 输出（结构合法 + 四要素齐全）。 */
    private AgentResult validLlmOutput() {
        return AgentResult.success("""
                [{"title":"产出报告","content":"c","deliverable":"研究报告成品文档",
                  "acceptance":"不少于 5 章完整内容","acceptanceKind":"STRUCTURAL"}]
                """, "stop", "llm", 100);
    }

    @Test
    @DisplayName("V146：PLANNING 任务缺少 attemptId → 直接跳过，不擅自补 UUID（补了幂等键就失效）")
    void shouldSkipWhenAttemptIdMissing() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(taskService.currentDecomposeAttempt(TASK_ID)).thenReturn(null);

        asyncService.executeDecompose(TASK_ID);

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(planDraftRoundService, never()).writeDrafts(anyLong(), anyString(), any(), any());
        // 不擅自回退：让租约巡检按统一判据处理，避免出现第三套判活逻辑
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
    }

    @Test
    @DisplayName("V146：落库命中幂等唯一索引 → 判定为「已消费」，不回退 PENDING，并收敛到待确认")
    void shouldTreatDuplicateKeyAsIdempotentHit() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        when(planDraftRoundService.writeDrafts(anyLong(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_plan_draft_code"));
        when(planDraftMapper.countByBatchKeyIncludeDeleted("attempt-test")).thenReturn(3L);

        asyncService.executeDecompose(TASK_ID);

        // 关键：绝不能走 rollbackToPending——那会把上一次已成功的拆解打成
        // 「草案在库 + task=PENDING」的死结（confirm 与重拆双向拒绝）
        verify(taskService, never()).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"), any(), any(), anyMap());
        // 收敛：草案已在库，把仍停在 PLANNING 的任务推到待确认
        verify(taskService).casStatusForAttempt(TASK_ID, TaskStatus.PLANNING, TaskStatus.AWAITING_PLAN_CONFIRM, "attempt-test");
    }

    @Test
    @DisplayName("唯一索引冲突但库中无草案 ⇒ 不是幂等命中，必须回退 PENDING（否则任务永卡 PLANNING）")
    void shouldRollbackWhenDuplicateKeyIsNotIdempotentHit() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        when(planDraftRoundService.writeDrafts(anyLong(), anyString(), any(), any()))
                // 典型场景：上一轮 reject 后暂存区没作废，本轮 code 与残留 ACTIVE 行同号
                .thenThrow(new DuplicateKeyException("uk_plan_draft_code"));
        // 本批一行都没落 ⇒ 冲突**不是**「本批已落过」
        when(planDraftMapper.countByBatchKeyIncludeDeleted("attempt-test")).thenReturn(0L);

        asyncService.executeDecompose(TASK_ID);

        // 必须回退：修正前这里走「不改状态」，任务永久停在 PLANNING，
        // 等租约超时才被巡检捡回，且用户完全看不到错误（2026-11-08 23:13 真机复现）
        verify(taskService).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"), any(), any(), anyMap());
        // 绝不能推到待确认——那等于告诉用户「拆解成功了」却一条草案都没有
        verify(taskService, never()).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.AWAITING_PLAN_CONFIRM, "attempt-test");
    }

    @Test
    @DisplayName("TD-16e-2：本批暂存区少 4 行（期望 7 落 3）⇒ 判残缺，回退 PENDING 并作废本批")
    void shouldDiscardIncompleteBatchAndRollback() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        // 期望 7 条，实际只有 3 条落了暂存区
        when(planAttemptService.findLatestByBatchKey("attempt-test"))
                .thenReturn(java.util.Optional.of(attemptWithExpectedCount(7)));
        when(planDraftRoundService.writeDrafts(anyLong(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_plan_draft_code"));
        when(planDraftMapper.countByBatchKeyIncludeDeleted("attempt-test")).thenReturn(3L);

        asyncService.executeDecompose(TASK_ID);

        // 残缺批次绝不能当已消费：那会让用户看到 3 条当全部，一确认就把残缺批转正
        verify(taskService).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(taskService, never()).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.AWAITING_PLAN_CONFIRM, "attempt-test");
        // 判定与清理必须成对，否则残留草案仍会被列表读出来
        verify(planDraftRoundService).discardBatch("attempt-test");
    }

    @Test
    @DisplayName("TD-16e-2：expected_count 为 null（V146 前的存量尝试）⇒ 判不了完整，回落存在性并放行")
    void shouldFallbackToExistenceWhenExpectedCountMissing() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        when(planAttemptService.findLatestByBatchKey("attempt-test"))
                .thenReturn(java.util.Optional.of(attemptWithExpectedCount(null)));
        when(planDraftRoundService.writeDrafts(anyLong(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_plan_draft_code"));
        when(planDraftMapper.countByBatchKeyIncludeDeleted("attempt-test")).thenReturn(3L);

        asyncService.executeDecompose(TASK_ID);

        // 升级当天所有在途任务都是这种形态：判不到位就全判残缺会一次性砖化一批任务
        verify(taskService).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.AWAITING_PLAN_CONFIRM, "attempt-test");
        verify(planDraftRoundService, never()).discardBatch(anyString());
    }

    @Test
    @DisplayName("TD-16e-2：暂存区 0 行 ⇒ 本批没落过，判失败回退并作废该批")
    void shouldTreatStagingRowsZeroAsNotConsumed() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        when(planAttemptService.findLatestByBatchKey("attempt-test"))
                .thenReturn(java.util.Optional.of(attemptWithExpectedCount(7)));
        when(planDraftRoundService.writeDrafts(anyLong(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk_plan_draft_code"));
        when(planDraftMapper.countByBatchKeyIncludeDeleted("attempt-test")).thenReturn(0L);

        asyncService.executeDecompose(TASK_ID);

        // 暂存区是唯一事实源：0 行 = 本批确实没落过，不能当已消费
        verify(taskService).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
        verify(planDraftRoundService).discardBatch("attempt-test");
    }

    @Test
    @DisplayName("TD-16e-2：落库前写期望条数（= 本批草案数），且早于暂存区写入")
    void shouldRecordExpectedCountBeforeSavingDrafts() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        when(planAttemptService.findLatestByBatchKey("attempt-test"))
                .thenReturn(java.util.Optional.of(attemptWithExpectedCount(null)));
        when(planAttemptService.recordExpectedCount(anyLong(), anyInt(), anyInt()))
                .thenReturn(true);
        staged(draft(11L));

        asyncService.executeDecompose(TASK_ID);

        // 1 条 LLM 产出 ⇒ 期望 1 条。写在暂存区落库之前是硬要求：
        // 写在后面，崩溃窗口里 expected_count 恒为 null，残缺批次永远识别不出来。
        verify(planAttemptService).recordExpectedCount(eq(7L), eq(1), eq(1));
        InOrder order = inOrder(planAttemptService, planDraftRoundService);
        order.verify(planAttemptService).recordExpectedCount(eq(7L), eq(1), eq(1));
        order.verify(planDraftRoundService).writeDrafts(anyLong(), anyString(), any(), any());
    }

    private PlanAttempt attemptWithExpectedCount(Integer expectedCount) {
        PlanAttempt a = new PlanAttempt();
        a.setId(7L);
        a.setTaskId(TASK_ID);
        a.setBatchKey("attempt-test");
        // 直接给 RUNNING：跳过 claim 分支，用例聚焦「撞键恢复的判据」
        a.setStatus(PlanAttemptStatus.RUNNING);
        a.setAttemptNo(1);
        a.setExpectedCount(expectedCount);
        return a;
    }

    @Test
    @DisplayName("V146 竞态分流：任务被租约巡检回收成 PENDING → 草案完整，推回待确认而不是报废")
    void shouldPushBackToAwaitingConfirmWhenReclaimedAsPending() {
        when(taskService.getView(TASK_ID))
                .thenReturn(planningTask())                      // executeDecompose 入口
                .thenReturn(taskView(TaskStatus.PENDING, null)); // 分流时读到已被回收
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        staged(draft(11L));
        when(taskService.casStatusForAttempt(eq(TASK_ID),
                eq(TaskStatus.PLANNING), eq(TaskStatus.AWAITING_PLAN_CONFIRM), anyString())).thenReturn(false);
        when(taskService.casStatusForAttempt(eq(TASK_ID),
                eq(TaskStatus.PENDING), eq(TaskStatus.AWAITING_PLAN_CONFIRM), anyString())).thenReturn(true);

        asyncService.executeDecompose(TASK_ID);

        // 纠正回待确认：用户等了十几分钟，AI 成果不该因为一次误杀白扔
        verify(taskService).casStatusForAttempt(TASK_ID, TaskStatus.PENDING, TaskStatus.AWAITING_PLAN_CONFIRM, "attempt-test");
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("V146 竞态分流：拆解完成时任务已被用户取消 → 抛错回滚草案，不留孤儿行")
    void shouldRollbackWhenTaskCancelledDuringDecompose() {
        when(taskService.getView(TASK_ID))
                .thenReturn(planningTask())
                .thenReturn(taskView(TaskStatus.CANCELLED, null));
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        staged(draft(11L));
        when(taskService.casStatusForAttempt(eq(TASK_ID),
                eq(TaskStatus.PLANNING), eq(TaskStatus.AWAITING_PLAN_CONFIRM), anyString())).thenReturn(false);

        asyncService.executeDecompose(TASK_ID);

        // 用户已取消：草案不该留在库里（整个落库事务回滚），且必须留失败审计
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"), any(), any(), anyMap());
    }

    @Test
    @DisplayName("V146 跨 attempt 竞态：本轮已被新一轮拆解取代（状态仍 PLANNING）→ 抛错回滚，不与新一轮草案混存")
    void shouldRollbackWhenAttemptSupersededByNewDecompose() {
        // 时序：本轮跑超租约 → 巡检推回 PENDING → 用户重新拆解（attempt 变了，状态又回 PLANNING）
        // → 本轮才苏醒并走到状态推进。三参 CAS 会命中新一轮的 PLANNING，
        // 四参 CAS（带 attemptId）不会。
        when(taskService.getView(TASK_ID))
                .thenReturn(planningTask())
                .thenReturn(taskView(TaskStatus.PLANNING, null));
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(validLlmOutput());
        staged(draft(11L));
        when(taskService.casStatusForAttempt(eq(TASK_ID),
                eq(TaskStatus.PLANNING), eq(TaskStatus.AWAITING_PLAN_CONFIRM), anyString())).thenReturn(false);

        asyncService.executeDecompose(TASK_ID);

        // 必须留失败审计（草案随事务回滚，没有 task_plan_generated）
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"), any(), any(), anyMap());
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_generated"), any(), any(), anyMap());
        // 失败回退用的是四参 CAS：真实实现里因 attemptId 已被新一轮覆盖而 0 行更新，
        // 所以不会把新一轮的 PLANNING 推回 PENDING（这正是加 attemptId 的目的）
        verify(taskService).casStatusForAttempt(
                TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING, "attempt-test");
    }
}

