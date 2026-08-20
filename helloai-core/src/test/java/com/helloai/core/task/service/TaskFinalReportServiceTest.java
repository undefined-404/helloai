package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskIterationService;
import com.helloai.core.task.service.impl.TaskFinalReportServiceImpl;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskFinalReportService 单元测试：Planner 整合报告生成编排
 * （前置校验、prompt 组装取数、写回三列、timeline 记录、自动触发跳过条件）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TaskFinalReportService 最终整合报告生成")
class TaskFinalReportServiceTest {

    private static final Long TASK_ID = 1L;

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
    private TaskIterationService taskIterationService;

    private final AgentDispatchProperties dispatchProperties = new AgentDispatchProperties();

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> subTaskQueryChain = mock(LambdaQueryChainWrapper.class);

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Task> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    private TaskFinalReportService service;

    /**
     * CAS 防重入使用 {@code new LambdaUpdateWrapper<Task>()}，其 lambda 解析依赖
     * MyBatis-Plus TableInfo 缓存；单测无 Spring 上下文，需手动注册 Task 的 TableInfo，
     * 否则构造 wrapper 时抛 "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new MybatisConfiguration(), ""), Task.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskFinalReportServiceImpl(taskService, subTaskService, plannerAgentPicker,
                platformAgentExecutionService, taskTimelineService, dispatchProperties,
                taskIterationService);

        when(subTaskService.lambdaQuery()).thenReturn(subTaskQueryChain);
        when(subTaskQueryChain.eq(any(), any())).thenReturn(subTaskQueryChain);
        when(subTaskQueryChain.orderByAsc(org.mockito.ArgumentMatchers.<SFunction<SubTask, ?>>any()))
                .thenReturn(subTaskQueryChain);
        when(subTaskQueryChain.list()).thenReturn(List.of());

        when(taskService.lambdaUpdate()).thenReturn(taskUpdateChain);
        when(taskUpdateChain.eq(any(), any())).thenReturn(taskUpdateChain);
        when(taskUpdateChain.set(any(), any())).thenReturn(taskUpdateChain);
        when(taskUpdateChain.update()).thenReturn(true);
        // CAS 防重入：置 GENERATING 默认成功（防重入用例内单独覆盖为 false）
        when(taskService.update(any())).thenReturn(true);
    }

    private Task doneTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setTitle("调度分析");
        task.setDescription("梳理调度链路");
        task.setStatus(TaskStatus.DONE);
        return task;
    }

    private Agent planner() {
        Agent agent = new Agent();
        agent.setId(9L);
        agent.setName("planner-llm");
        agent.setRole(AgentRole.PLANNER);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        return agent;
    }

    private SubTask doneSubTask(long id, String title, String output) {
        SubTask st = new SubTask();
        st.setId(id);
        st.setTaskId(TASK_ID);
        st.setTitle(title);
        st.setStatus(SubTaskStatus.DONE);
        if (output != null) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("lastExecution", Map.of("output", output));
            st.setContext(ctx);
        }
        return st;
    }

    @Test
    @DisplayName("生成成功：prompt 含任务与子任务产出，报告写回三列并记录 generated 事件")
    void shouldGenerateAndPersistReport() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告\n\n全局结论", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
        assertThat(taskCaptor.getValue().getUserPrompt())
                .contains("调度分析").contains("架构梳理").contains("# 架构梳理产出");
        assertThat(taskCaptor.getValue().getContext()).containsEntry("scene", "task_final_report");
        // 写回只更新四列（final_report / agent_id / time / status）
        verify(taskUpdateChain, org.mockito.Mockito.times(4)).set(any(), any());
        verify(taskUpdateChain).update();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("任务不存在抛 BizException(404)")
    void shouldThrowWhenTaskMissing() {
        when(taskService.getById(TASK_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    @DisplayName("非 DONE 任务拒绝生成")
    void shouldRejectWhenTaskNotDone() {
        Task task = doneTask();
        task.setStatus(TaskStatus.IN_PROGRESS);
        when(taskService.getById(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DONE");
        verify(plannerAgentPicker, never()).pickForTask(any());
    }

    @Test
    @DisplayName("无有产出的 DONE 子任务时拒绝（含 DONE 但产出为空）")
    void shouldRejectWhenNoSubTaskOutput() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "无产出项", null)));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有可整合的子任务产出");
        verify(plannerAgentPicker, never()).pickForTask(any());
    }

    @Test
    @DisplayName("LLM 调用失败：记录 failed 事件并抛 BizException，不写回")
    void shouldRecordFailedEventWhenLlmFails() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("provider timeout", "error", "llm"));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("LLM 调用失败");
        verify(taskService, never()).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_failed"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("token 超限降档重试：首档命中上下文上限后收紧截断重试并成功写回")
    void shouldDowngradeAndRetryOnTokenLimitError() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(planner());
        // 产出超过第二档 2000 字符，确保重试 prompt 确实被收紧
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "X".repeat(9000))));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure(
                        "400 - Your request exceeded model token limit: 8192", "error", "llm"))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, org.mockito.Mockito.times(2))
                .executeSync(any(Agent.class), taskCaptor.capture());
        // 第二次重试的 prompt 明显短于首次（截断从 8000 收紧到 2000）
        List<AgentTask> calls = taskCaptor.getAllValues();
        assertThat(calls.get(1).getUserPrompt().length())
                .isLessThan(calls.get(0).getUserPrompt().length());
        verify(taskUpdateChain).update();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_generated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_failed"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("token 超限降到最后一档仍失败：尝试全部阶梯后记 failed 事件并抛出")
    void shouldFailAfterAllTiersOnPersistentTokenLimitError() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure(
                        "400 - Your request exceeded model token limit: 8192", "error", "llm"));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("token limit");
        // 三档阶梯全部尝试后才失败
        verify(platformAgentExecutionService, org.mockito.Mockito.times(3))
                .executeSync(any(Agent.class), any(AgentTask.class));
        verify(taskService, never()).lambdaUpdate();
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_failed"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("自动触发：开关关闭时直接跳过")
    void shouldSkipAutoWhenDisabled() {
        dispatchProperties.setAutoFinalReportEnabled(false);

        service.onTaskAutoCompleted(new TaskAutoCompletedEvent(TASK_ID));

        verify(taskService, never()).getById(any());
        verify(plannerAgentPicker, never()).pickForTask(any());
    }

    @Test
    @DisplayName("自动触发：已有报告时幂等跳过，不重复调 LLM")
    void shouldSkipAutoWhenReportAlreadyExists() {
        Task task = doneTask();
        task.setFinalReport("# 已有报告");
        when(taskService.getById(TASK_ID)).thenReturn(task);

        service.onTaskAutoCompleted(new TaskAutoCompletedEvent(TASK_ID));

        verify(plannerAgentPicker, never()).pickForTask(any());
    }

    @Test
    @DisplayName("自动触发：生成异常被吞掉不外抛（手动端点兜底）")
    void shouldSwallowExceptionOnAutoGenerate() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        // 无子任务产出 → generate 内部抛 BizException，自动路径应吞掉

        service.onTaskAutoCompleted(new TaskAutoCompletedEvent(TASK_ID));

        verify(plannerAgentPicker, never()).pickForTask(any());
    }

    @Test
    @DisplayName("防重入：已有生成在途（CAS 置 GENERATING 失败）时抛错且不调 LLM")
    void shouldRejectWhenAlreadyGenerating() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(taskService.update(any())).thenReturn(false);

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("正在生成中");
        verify(plannerAgentPicker, never()).pickForTask(any());
        verify(platformAgentExecutionService, never())
                .executeSync(any(Agent.class), any(AgentTask.class));
    }

    @Test
    @DisplayName("LLM 最终失败：状态置 FAILED（CAS 置 GENERATING + 失败置 FAILED 共两次 update）")
    void shouldMarkFailedStatusWhenLlmFails() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("provider timeout", "error", "llm"));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class);

        // 第一次 update = CAS 置 GENERATING；第二次 = markFailed 置 FAILED
        verify(taskService, org.mockito.Mockito.times(2)).update(any());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_failed"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("自动触发：报告生成中（GENERATING）时跳过，避免与手动路径并发")
    void shouldSkipAutoWhenGenerating() {
        Task task = doneTask();
        task.setFinalReportStatus(FinalReportStatus.GENERATING);
        when(taskService.getById(TASK_ID)).thenReturn(task);

        service.onTaskAutoCompleted(new TaskAutoCompletedEvent(TASK_ID));

        verify(plannerAgentPicker, never()).pickForTask(any());
        verify(taskService, never()).update(any());
    }
}
