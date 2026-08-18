package com.helloai.core.planner;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.impl.PlannerAnalysisServiceImpl;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerAnalysisService 单元测试（LLM mock）：
 * 正常拆解 / markdown fence 容错 / JSON 解析失败回退 / 重复触发拒绝 / 无 Planner Agent、
 * confirm / reject 状态流转。
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
    private SubTaskMapper subTaskMapper;

    @Mock
    private PlannerAgentPicker plannerAgentPicker;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private SubTaskDispatchService subTaskDispatchService;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    private PlannerAnalysisService plannerAnalysisService;

    // lambdaQuery / lambdaUpdate 链式 mock（项目内首例：手动 stub 链式返回自身）
    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> subTaskQueryChain = mock(LambdaQueryChainWrapper.class);

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Task> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        plannerAnalysisService = new PlannerAnalysisServiceImpl(
                taskService, subTaskService, subTaskMapper, plannerAgentPicker,
                platformAgentExecutionService, taskTimelineService,
                subTaskDispatchService, taskRunningSpecService, new ObjectMapper());

        lenient().when(subTaskService.lambdaQuery()).thenReturn(subTaskQueryChain);
        lenient().when(subTaskQueryChain.eq(any(), any())).thenReturn(subTaskQueryChain);
        lenient().when(subTaskQueryChain.ne(any(), any())).thenReturn(subTaskQueryChain);
        lenient().when(subTaskQueryChain.count()).thenReturn(0L);

        lenient().when(taskService.lambdaUpdate()).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.eq(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.set(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.update()).thenReturn(true);

        // finishConfirm 新增的 updateById + RunningSpec 初始化
        lenient().when(taskService.updateById(any(Task.class))).thenReturn(true);
        lenient().doNothing().when(taskRunningSpecService).initialize(anyLong(), any());
    }

    private Task pendingTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setTitle("搭建报表模块");
        task.setDescription("需要一个日报统计模块");
        task.setStatus(TaskStatus.PENDING);
        return task;
    }

    private Task planningTask() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.PLANNING);
        return task;
    }

    private Agent llmPlanner() {
        Agent agent = new Agent();
        agent.setId(9L);
        agent.setName("planner-llm");
        agent.setRole(AgentRole.PLANNER);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        return agent;
    }

    private SubTask draft(long id) {
        SubTask subTask = new SubTask();
        subTask.setId(id);
        subTask.setTaskId(TASK_ID);
        subTask.setStatus(SubTaskStatus.PENDING_PLAN_REVIEW);
        return subTask;
    }

    private Page<SubTask> pageOf(List<SubTask> records) {
        Page<SubTask> page = new Page<>();
        page.setRecords(records);
        return page;
    }

    // ══════════════════════════════════════════════════════════════
    //  decompose：正常拆解
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正常拆解：markdown fence 容错解析，草案落库 PENDING_PLAN_REVIEW 并记录 timeline")
    void shouldDecomposeAndPersistDrafts() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"设计表结构","content":"建表","deliverable":"DDL","acceptance":"评审通过","priority":"high"},
                          {"title":"实现统计接口","priority":"不合法优先级"}
                        ]
                        ```
                        """, "stop", "llm", 100));
        // V27 起 decompose 保存后按 items 顺序重加载草案（防御 saveBatch 实体 ID 未回填）；
        // mock 不落库，stub list 返回带 id 与审计上下文的"重加载结果"
        SubTask reloaded1 = draft(11L);
        reloaded1.setPriority("HIGH");
        reloaded1.setContext(Map.of("plannerAgentId", 9L));
        SubTask reloaded2 = draft(12L);
        reloaded2.setPriority("MEDIUM");
        reloaded2.setContext(Map.of("plannerAgentId", 9L));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(reloaded1, reloaded2));

        List<SubTask> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).hasSize(2);
        assertThat(drafts).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(SubTaskStatus.PENDING_PLAN_REVIEW);
            assertThat(d.getTaskId()).isEqualTo(TASK_ID);
            assertThat(d.getContext()).containsEntry("plannerAgentId", 9L);
        });
        assertThat(drafts.get(0).getPriority()).isEqualTo("HIGH");
        // 非法优先级归一化为 MEDIUM
        assertThat(drafts.get(1).getPriority()).isEqualTo("MEDIUM");

        ArgumentCaptor<List<SubTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  decompose：失败与拒绝路径
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("JSON 解析失败：Task 回退 PENDING 并记录 task_plan_failed，不落库")
    void shouldRollbackWhenLlmOutputIsNotJson() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("抱歉，我无法完成拆解。", "stop", "llm", 10));

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("JSON 解析失败");

        verify(subTaskService, never()).saveBatch(any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        // CAS 推进 + 失败回退各走一次 lambdaUpdate
        verify(taskService, org.mockito.Mockito.times(2)).lambdaUpdate();
    }

    @Test
    @DisplayName("LLM 调用失败：抛 BizException 并回退")
    void shouldRollbackWhenLlmCallFails() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.failure("provider timeout", "error", "llm"));

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("LLM 调用失败");
        verify(subTaskService, never()).saveBatch(any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("非 PENDING 任务拒绝拆解")
    void shouldRejectDecomposeWhenTaskNotPending() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.IN_PROGRESS);
        when(taskService.getById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PENDING");
        verify(taskService, never()).lambdaUpdate();
    }

    @Test
    @DisplayName("已存在非 CANCELLED 子任务时拒绝重复拆解")
    void shouldRejectDecomposeWhenSubTasksAlreadyExist() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(subTaskQueryChain.count()).thenReturn(3L);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许重复拆解");
        verify(taskService, never()).lambdaUpdate();
        // §6.100: 有非 CANCELLED 残留时不得触碰物理删除
        verify(subTaskMapper, never()).physicalDeleteByTaskId(anyLong());
    }

    @Test
    @DisplayName("§6.100: 仅残留 CANCELLED 旧草案时，拆解前物理删除再生成新草案")
    void shouldPhysicallyDeleteCancelledDraftsBeforeRedecompose() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        // 第一次 count（非 CANCELLED）= 0，第二次 count（CANCELLED）= 2，按调用顺序 stub
        when(subTaskQueryChain.count()).thenReturn(0L, 2L);
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"设计表结构","content":"建表","deliverable":"DDL","acceptance":"评审通过","priority":"high"},
                          {"title":"实现统计接口","priority":"不合法优先级"}
                        ]
                        ```
                        """, "stop", "llm", 100));
        SubTask reloaded1 = draft(11L);
        SubTask reloaded2 = draft(12L);
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(reloaded1, reloaded2));

        List<SubTask> drafts = plannerAnalysisService.decompose(TASK_ID);

        // 物理删除发生在 LLM 调用前，且新草案正常生成
        verify(subTaskMapper).physicalDeleteByTaskId(TASK_ID);
        assertThat(drafts).hasSize(2);
    }

    @Test
    @DisplayName("§6.100: 无 CANCELLED 残留时不触发物理删除（正常首次拆解不受影响）")
    void shouldNotDeleteWhenNoCancelledDrafts() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"设计表结构","content":"建表","deliverable":"DDL","acceptance":"评审通过","priority":"high"},
                          {"title":"实现统计接口","priority":"不合法优先级"}
                        ]
                        ```
                        """, "stop", "llm", 100));
        SubTask reloaded1 = draft(11L);
        SubTask reloaded2 = draft(12L);
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(reloaded1, reloaded2));

        List<SubTask> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).hasSize(2);
        verify(subTaskMapper, never()).physicalDeleteByTaskId(anyLong());
    }

    @Test
    @DisplayName("§6.100: 幽灵依赖防御——依赖回写引用未落库 ID 时整批拒绝并回退")
    void shouldRejectDecomposeWhenDependsOnPointsToMissingDraft() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 第 2 条依赖第 1 条（序号 1 → 重加载 drafts 的 id=11L）
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"第一步","content":"准备","dependsOn":[]},
                          {"title":"第二步","content":"执行","dependsOn":[1]}
                        ]
                        ```
                        """, "stop", "llm", 100));
        SubTask reloaded1 = draft(11L);
        SubTask reloaded2 = draft(12L);
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(reloaded1, reloaded2));
        // 幽灵场景：依赖 ID 11 在所有草案之外，listByIds 查不到
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of());

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("依赖指向不存在的草案");

        // 幽灵依赖不得静默落库：任何依赖回写都不执行
        verify(subTaskService, never()).updateDependsOn(anyLong(), any());
        // 拆解失败回退 PENDING + task_plan_failed
        verify(taskService, times(2)).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("§6.100: 依赖回写目标全部存在时正常落库（序号→真实 id 映射）")
    void shouldApplyDependsOnWhenAllTargetsExist() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"第一步","content":"准备","dependsOn":[]},
                          {"title":"第二步","content":"执行","dependsOn":[1]}
                        ]
                        ```
                        """, "stop", "llm", 100));
        SubTask reloaded1 = draft(11L);
        SubTask reloaded2 = draft(12L);
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(reloaded1, reloaded2));
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(reloaded1));

        List<SubTask> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).hasSize(2);
        // 序号 1 → 真实 id 11L，回写第 2 条草案
        verify(subTaskService).updateDependsOn(eq(12L), eq(List.of(11L)));
    }

    @Test
    @DisplayName("CAS 失败（并发拆解中）时拒绝")
    void shouldRejectWhenCasLost() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(taskUpdateChain.update()).thenReturn(false);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他请求拆解中");
    }

    @Test
    @DisplayName("选型器无可用 Planner 时报错并回退")
    void shouldFailWhenNoPlatformPlannerAgent() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenThrow(new BizException(
                "无可用的平台内 Planner Agent（需要 role=PLANNER 且 accessType=API_KEY_LLM）；"
                        + "请先在 Agent 管理中注册，或改用外部 Planner Agent 手工创建子任务"));

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无可用的平台内 Planner Agent");
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  confirmPlan / rejectPlan
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("confirm：草案批量转正 PENDING，Task → IN_PROGRESS，无条件逐条分发")
    void shouldConfirmDraftsAndDispatch() {
        Task task = planningTask();
        when(taskService.getById(TASK_ID)).thenReturn(task);
        List<SubTask> drafts = List.of(draft(1L), draft(2L));
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0))
                .thenReturn(pageOf(drafts));
        when(subTaskService.getById(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));

        List<SubTask> confirmed = plannerAnalysisService.confirmPlan(TASK_ID);

        assertThat(confirmed).hasSize(2);
        verify(subTaskService).changeStatus(eq(1L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
        verify(subTaskService).changeStatus(eq(2L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        verify(taskService).updateById(task);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_confirmed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(1L, AgentRole.EXECUTOR);
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(2L, AgentRole.EXECUTOR);
    }

    @Test
    @DisplayName("confirm：单条分发失败不阻断确认与其余分发")
    void shouldConfirmEvenWhenDispatchFails() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        List<SubTask> drafts = List.of(draft(1L), draft(2L));
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0))
                .thenReturn(pageOf(drafts));
        when(subTaskService.getById(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));
        doThrow(new BizException("无可用 Agent"))
                .when(subTaskDispatchService).dispatchPendingSubTaskAuto(1L, AgentRole.EXECUTOR);

        List<SubTask> confirmed = plannerAnalysisService.confirmPlan(TASK_ID);

        assertThat(confirmed).hasSize(2);
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(2L, AgentRole.EXECUTOR);
    }

    @Test
    @DisplayName("confirm：任务带 SLA 时按 确认时刻+slaMinutes 下发子任务 deadline，先持久化再转正")
    void shouldAssignDeadlineFromTaskSlaWhenConfirming() {
        Task task = planningTask();
        task.setSlaMinutes(60);
        when(taskService.getById(TASK_ID)).thenReturn(task);
        List<SubTask> drafts = List.of(draft(1L), draft(2L));
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0))
                .thenReturn(pageOf(drafts));
        when(subTaskService.getById(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));

        OffsetDateTime before = OffsetDateTime.now();
        plannerAnalysisService.confirmPlan(TASK_ID);
        OffsetDateTime after = OffsetDateTime.now();

        // A0-7：deadline 必须在 changeStatus 前落库（changeStatus 内部重查库后全字段更新，
        // 未落库的 deadline 会被覆盖丢失），取值区间 [now, now+60min]，序列化 ISO8601 带时区偏移
        ArgumentCaptor<SubTask> captor = ArgumentCaptor.forClass(SubTask.class);
        verify(subTaskService, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(st -> {
            assertThat(st.getDeadline()).isNotNull();
            assertThat(st.getDeadline()).isBetween(before.plusMinutes(59), after.plusMinutes(60));
            assertThat(st.getDeadline().toString()).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        });
        // 转正仍逐条执行，与 deadline 下发互不干扰
        verify(subTaskService).changeStatus(eq(1L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
        verify(subTaskService).changeStatus(eq(2L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
    }

    @Test
    @DisplayName("confirm：非 PLANNING 状态或无草案时拒绝")
    void shouldRejectConfirmOnIllegalState() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PLANNING");

        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0))
                .thenReturn(pageOf(List.of()));
        // recoverAlreadyConfirmed 会查 PENDING 子任务，未 stub 则返回 null 导致 NPE
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING, null, null, 0))
                .thenReturn(pageOf(List.of()));
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有待确认的规划草案");
    }

    @Test
    @DisplayName("reject：草案全部翻 CANCELLED，Task 回退 PENDING")
    void shouldRejectDraftsAndRollbackTask() {
        Task task = planningTask();
        when(taskService.getById(TASK_ID)).thenReturn(task);
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0))
                .thenReturn(pageOf(List.of(draft(1L), draft(2L), draft(3L))));

        int cancelled = plannerAnalysisService.rejectPlan(TASK_ID);

        assertThat(cancelled).isEqualTo(3);
        verify(subTaskService).changeStatus(eq(1L), eq(SubTaskStatus.CANCELLED), isNull(), anyMap());
        verify(subTaskService).changeStatus(eq(3L), eq(SubTaskStatus.CANCELLED), isNull(), anyMap());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        verify(taskService).updateById(task);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_rejected"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("reject：非 PLANNING 状态拒绝")
    void shouldRejectRejectPlanOnIllegalState() {
        Task task = pendingTask();
        task.setStatus(TaskStatus.DONE);
        when(taskService.getById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> plannerAnalysisService.rejectPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PLANNING");
    }

    // ════════════════════════════════════════════════════════════
    //  validateDependencies：依赖环校验（V27）
    // ════════════════════════════════════════════════════════════

    private PlannerAnalysisService.PlanDraftItem item(List<Integer> dependsOn) {
        PlannerAnalysisService.PlanDraftItem it = new PlannerAnalysisService.PlanDraftItem();
        it.setTitle("t");
        it.setContent("c");
        it.setDependsOn(dependsOn);
        return it;
    }

    @Test
    @DisplayName("validateDependencies：合法 DAG（链式+汇聚）通过，null/空依赖视为无依赖")
    void shouldAcceptValidDag() {
        // 1 ← 2，(1,2) ← 3，4 无依赖
        List<PlannerAnalysisService.PlanDraftItem> items = List.of(
                item(null), item(List.of(1)), item(List.of(1, 2)), item(List.of()));
        plannerAnalysisService.validateDependencies(items); // 不抛即通过
    }

    @Test
    @DisplayName("validateDependencies：成环整批拒绝")
    void shouldRejectCyclicDependencies() {
        // 1→2→3→1 成环
        List<PlannerAnalysisService.PlanDraftItem> items = List.of(
                item(List.of(3)), item(List.of(1)), item(List.of(2)));
        assertThatThrownBy(() -> plannerAnalysisService.validateDependencies(items))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("循环依赖");
    }

    @Test
    @DisplayName("validateDependencies：序号越界/自引用拒绝")
    void shouldRejectOutOfRangeAndSelfReference() {
        assertThatThrownBy(() -> plannerAnalysisService.validateDependencies(
                List.of(item(List.of(5)), item(null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("依赖序号非法");

        assertThatThrownBy(() -> plannerAnalysisService.validateDependencies(
                List.of(item(List.of(1)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得依赖自身");
    }
}
