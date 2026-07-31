package com.helloai.core.planner;

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
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private PlannerAgentPicker plannerAgentPicker;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private SubTaskDispatchService subTaskDispatchService;

    private PlannerAnalysisService plannerAnalysisService;

    // lambdaQuery / lambdaUpdate 链式 mock（项目内首例：手动 stub 链式返回自身）
    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> subTaskQueryChain = mock(LambdaQueryChainWrapper.class);

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Task> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        plannerAnalysisService = new PlannerAnalysisService(
                taskService, subTaskService, plannerAgentPicker,
                platformAgentExecutionService, taskTimelineService,
                subTaskDispatchService, new ObjectMapper());

        lenient().when(subTaskService.lambdaQuery()).thenReturn(subTaskQueryChain);
        lenient().when(subTaskQueryChain.eq(any(), any())).thenReturn(subTaskQueryChain);
        lenient().when(subTaskQueryChain.ne(any(), any())).thenReturn(subTaskQueryChain);
        lenient().when(subTaskQueryChain.count()).thenReturn(0L);

        lenient().when(taskService.lambdaUpdate()).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.eq(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.set(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.update()).thenReturn(true);
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
    @DisplayName("confirm：非 PLANNING 状态或无草案时拒绝")
    void shouldRejectConfirmOnIllegalState() {
        when(taskService.getById(TASK_ID)).thenReturn(pendingTask());
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PLANNING");

        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(subTaskService.list(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0))
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
