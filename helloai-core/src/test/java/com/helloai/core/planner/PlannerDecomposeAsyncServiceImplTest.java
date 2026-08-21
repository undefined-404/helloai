package com.helloai.core.planner;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
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
import com.helloai.core.planner.service.impl.PlannerDecomposeAsyncServiceImpl;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerDecomposeAsyncServiceImpl 单元测试（拆解异步化改造，LLM 段用例迁移自
 * PlannerAnalysisServiceTest）：幂等守卫 / 成功落库 / markdown fence 容错 /
 * JSON 解析失败回退 / LLM 调用失败回退 / 幽灵依赖 / dependsOn 回写 /
 * validateDependencies 依赖环校验。
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

    private PlannerDecomposeAsyncServiceImpl asyncService;

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Task> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        asyncService = new PlannerDecomposeAsyncServiceImpl(
                taskService, subTaskService, plannerAgentPicker,
                platformAgentExecutionService, taskTimelineService, new ObjectMapper());

        lenient().when(taskService.lambdaUpdate()).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.eq(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.set(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.update()).thenReturn(true);
    }

    private Task planningTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setTitle("搭建报表模块");
        task.setDescription("需要一个日报统计模块");
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

    // ══════════════════════════════════════════════════════════════
    //  幂等守卫：仅 PLANNING 任务才执行
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("任务已离开 PLANNING（超时回收/已确认）时跳过，不触碰 LLM")
    void shouldSkipWhenTaskNotPlanning() {
        Task task = planningTask();
        task.setStatus(TaskStatus.PENDING);
        when(taskService.getById(TASK_ID)).thenReturn(task);

        asyncService.executeDecompose(TASK_ID);

        verify(plannerAgentPicker, never()).pickForTask(anyLong());
        verify(platformAgentExecutionService, never())
                .executeSync(any(Agent.class), any(AgentTask.class));
    }

    @Test
    @DisplayName("任务不存在时跳过")
    void shouldSkipWhenTaskNotFound() {
        when(taskService.getById(TASK_ID)).thenReturn(null);

        asyncService.executeDecompose(TASK_ID);

        verify(plannerAgentPicker, never()).pickForTask(anyLong());
    }

    // ══════════════════════════════════════════════════════════════
    //  正常拆解：成功落库
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正常拆解：markdown fence 容错解析，草案落库 PENDING_PLAN_REVIEW，start/end/generated timeline 齐全")
    void shouldDecomposeAndPersistDrafts() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
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
        // saveBatch 后按 items 顺序重加载草案（防御实体 ID 未回填）；
        // mock 不落库，stub list 返回带 id 与审计上下文的"重加载结果"
        SubTask reloaded1 = draft(11L);
        reloaded1.setPriority("HIGH");
        reloaded1.setContext(Map.of("plannerAgentId", 9L));
        SubTask reloaded2 = draft(12L);
        reloaded2.setPriority("MEDIUM");
        reloaded2.setContext(Map.of("plannerAgentId", 9L));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(reloaded1, reloaded2));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveBatch(captor.capture());
        List<SubTask> drafts = captor.getValue();
        assertThat(drafts).hasSize(2);
        assertThat(drafts).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(SubTaskStatus.PENDING_PLAN_REVIEW);
            assertThat(d.getTaskId()).isEqualTo(TASK_ID);
            assertThat(d.getContext()).containsEntry("plannerAgentId", 9L);
        });
        assertThat(drafts.get(0).getPriority()).isEqualTo("HIGH");
        // 非法优先级归一化为 MEDIUM
        assertThat(drafts.get(1).getPriority()).isEqualTo("MEDIUM");

        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_llm_call_start"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("task_plan_llm_call_end 事件携带耗时毫秒、finishReason、tokenUsage")
    void shouldRecordLlmCallEndWithObservabilityFields() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步"}]
                        """, "stop", "llm", 100));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(draft(11L)));

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
    //  契约先行拆解（Phase 2）：contract 字段解析与落库
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("contract=true 落库 isContract=1；false/缺省/字符串布尔宽容解析降级为 0")
    void shouldParseContractFlagToIsContract() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"契约定义","content":"接口签名","contract":true,"dependsOn":[]},
                          {"title":"下游实现","content":"照契约实现","contract":false,"dependsOn":[1]},
                          {"title":"普通子任务","content":"缺省 contract","dependsOn":[1]},
                          {"title":"字符串布尔","content":"contract 给字符串","contract":"true","dependsOn":[1]}
                        ]
                        """, "stop", "llm", 100));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(
                draft(11L), draft(12L), draft(13L), draft(14L)));
        when(subTaskService.listByIds(List.of(11L))).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveBatch(captor.capture());
        List<SubTask> drafts = captor.getValue();
        assertThat(drafts).hasSize(4);
        // 布尔 true / 字符串 "true" → 1；false/缺省 → 0（Boolean.TRUE.equals 语义降级）
        assertThat(drafts).extracting(SubTask::getIsContract)
                .containsExactly(1, 0, 0, 1);
    }

    @Test
    @DisplayName("contract 完全非法值（非布尔）：整批解析失败回退 PENDING，不落库（可重拆恢复）")
    void shouldRollbackWhenContractFlagIsInvalid() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"契约定义","content":"接口签名","contract":"yes","dependsOn":[]},
                          {"title":"下游实现","dependsOn":[1]}
                        ]
                        """, "stop", "llm", 100));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService, never()).saveBatch(any());
        verify(taskService).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  失败路径：内部闭环回退 PENDING（不再抛出）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("JSON 解析失败：回退 PENDING 并记录 task_plan_failed，不落库")
    void shouldRollbackWhenLlmOutputIsNotJson() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.success("抱歉，我无法完成拆解。", "stop", "llm", 10));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService, never()).saveBatch(any());
        // 失败回退走 CAS（lambdaUpdate）
        verify(taskService).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("LLM 调用失败：回退 PENDING 并记录 task_plan_failed")
    void shouldRollbackWhenLlmCallFails() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class))).thenReturn(
                AgentResult.failure("provider timeout", "error", "llm"));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService, never()).saveBatch(any());
        verify(taskService).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("选型器无可用 Planner 时：回退 PENDING 并记录 task_plan_failed")
    void shouldRollbackWhenNoPlatformPlannerAgent() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenThrow(new BizException(
                "无可用的平台内 Planner Agent（需要 role=PLANNER 且 accessType=API_KEY_LLM）；"
                        + "请先在 Agent 管理中注册，或改用外部 Planner Agent 手工创建子任务"));

        asyncService.executeDecompose(TASK_ID);

        verify(taskService).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.100 幽灵依赖防御 / dependsOn 回写
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.100: 幽灵依赖防御——依赖回写引用未落库 ID 时整批拒绝并回退")
    void shouldRejectWhenDependsOnPointsToMissingDraft() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
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

        asyncService.executeDecompose(TASK_ID);

        // 幽灵依赖不得静默落库：任何依赖回写都不执行
        verify(subTaskService, never()).updateDependsOn(anyLong(), any());
        // 拆解失败回退 PENDING + task_plan_failed
        verify(taskService).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("§6.100: 依赖回写目标全部存在时正常落库（序号→真实 id 映射）")
    void shouldApplyDependsOnWhenAllTargetsExist() {
        when(taskService.getById(TASK_ID)).thenReturn(planningTask());
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

        asyncService.executeDecompose(TASK_ID);

        // 序号 1 → 真实 id 11L，回写第 2 条草案
        verify(subTaskService).updateDependsOn(eq(12L), eq(List.of(11L)));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  validateDependencies：依赖环校验
    // ══════════════════════════════════════════════════════════════

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
        asyncService.validateDependencies(items); // 不抛即通过
    }

    @Test
    @DisplayName("validateDependencies：成环整批拒绝")
    void shouldRejectCyclicDependencies() {
        // 1→2→3→1 成环
        List<PlannerAnalysisService.PlanDraftItem> items = List.of(
                item(List.of(3)), item(List.of(1)), item(List.of(2)));
        assertThatThrownBy(() -> asyncService.validateDependencies(items))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("循环依赖");
    }

    @Test
    @DisplayName("validateDependencies：序号越界/自引用拒绝")
    void shouldRejectOutOfRangeAndSelfReference() {
        assertThatThrownBy(() -> asyncService.validateDependencies(
                List.of(item(List.of(5)), item(null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("依赖序号非法");

        assertThatThrownBy(() -> asyncService.validateDependencies(
                List.of(item(List.of(1)))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得依赖自身");
    }
}
