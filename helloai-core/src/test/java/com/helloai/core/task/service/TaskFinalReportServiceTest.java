package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
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
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.port.TaskPlannerPickerPort;
import com.helloai.core.task.service.TaskIterationService;
import com.helloai.core.task.service.impl.FinalReportPersistService;
import com.helloai.core.task.service.impl.TaskFinalReportServiceImpl;
import com.helloai.core.task.spec.ExecutionRecord;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private TaskPlannerPickerPort plannerPickerPort;
    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private TaskIterationService taskIterationService;
    @Mock
    private TaskRunningSpecService taskRunningSpecService;
    @Mock
    private AttachmentService attachmentService;
    /**
     * 报告写回 + 审查触发的事务边界（§12.2）。写回已从本类抽出，故单测里替换为 mock，
     * 断言由「taskUpdateChain.set/update」改为「persistAndRequestReview 的入参」。
     */
    @Mock
    private FinalReportPersistService finalReportPersistService;

    private final AgentDispatchProperties dispatchProperties = new AgentDispatchProperties();

    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTask> subTaskQueryChain = mock(LambdaQueryChainWrapper.class);

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
        service = new TaskFinalReportServiceImpl(taskService, subTaskService, plannerPickerPort,
                platformAgentExecutionService, taskTimelineService, dispatchProperties,
                taskIterationService, taskRunningSpecService, attachmentService,
                finalReportPersistService);

        when(subTaskService.lambdaQuery()).thenReturn(subTaskQueryChain);
        when(subTaskQueryChain.eq(any(), any())).thenReturn(subTaskQueryChain);
        when(subTaskQueryChain.orderByAsc(org.mockito.ArgumentMatchers.<SFunction<SubTask, ?>>any()))
                .thenReturn(subTaskQueryChain);
        when(subTaskQueryChain.list()).thenReturn(List.of());

        // CAS 防重入：置 GENERATING 默认成功（防重入用例内单独覆盖为 false）
        when(taskService.update(any())).thenReturn(true);
        // 写回 + 审查触发事务边界：默认写回成功（CAS 未命中场景单独覆盖为 false）
        when(finalReportPersistService.persistAndRequestReview(
                any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenReturn(true);
    }

    private Task doneTask() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setTitle("调度分析");
        task.setDescription("梳理调度链路");
        task.setStatus(TaskStatus.DONE);
        // §12.5 状态机校验锚点：报告已落地（DONE）是 generate/rollback 的常规起点
        task.setFinalReportStatus(FinalReportStatus.DONE);
        return task;
    }

    /**
     * 抓取写回 + 审查触发事务边界的调用次数与轮次序列。
     * 写回已从本类抽到 {@link FinalReportPersistService}，故「attempt 轮次」这一原由事件承载的
     * 断言锚点改为直接断言 {@code persistAndRequestReview} 的入参。
     */
    private List<Integer> capturePersistAttempts(int expectedTimes) {
        ArgumentCaptor<Integer> attemptCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(finalReportPersistService, times(expectedTimes)).persistAndRequestReview(
                any(), anyString(), any(), any(), attemptCaptor.capture(), anyInt(), anyBoolean());
        return attemptCaptor.getAllValues();
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

    /** 最小可直读文本附件。 */
    private Attachment textAttachment(long id, String name, String content) {
        Attachment att = new Attachment();
        att.setId(id);
        att.setFileName(name);
        att.setMimeType("text/plain");
        att.setFileType("txt");
        when(attachmentService.isContentLoadable(att)).thenReturn(true);
        when(attachmentService.loadContent(id)).thenReturn(content.getBytes(StandardCharsets.UTF_8));
        return att;
    }

    /** 执行生成并用 ArgumentCaptor 抓取最后一次调用的 userPrompt（3C 后第 1 次为出纲调用，取主链正文渲染）。 */
    private String captureFinalPrompt() {
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, atLeastOnce())
                .executeSync(any(Agent.class), taskCaptor.capture());
        List<AgentTask> calls = taskCaptor.getAllValues();
        return calls.get(calls.size() - 1).getUserPrompt();
    }

    @Test
    @DisplayName("生成成功：prompt 含任务与子任务产出，报告写回三列并记录 generated 事件")
    void shouldGenerateAndPersistReport() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告\n\n全局结论", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, atLeastOnce())
                .executeSync(any(Agent.class), taskCaptor.capture());
        assertThat(taskCaptor.getValue().getUserPrompt())
                .contains("调度分析").contains("架构梳理").contains("# 架构梳理产出");
        assertThat(taskCaptor.getValue().getContext()).containsEntry("scene", "task_final_report");
        // 写回（含 §12.1 prev 槽换入）+ L1/L2 审查触发同事务委托给 FinalReportPersistService：
        // 报告正文 / 生成者 / 轮次 / 子任务数 / 审查开关一并透传
        verify(finalReportPersistService).persistAndRequestReview(
                eq(TASK_ID), eq("# 整合报告\n\n全局结论"), eq(9L), any(), eq(1), eq(1), eq(true));
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
        verify(plannerPickerPort, never()).pickForTask(any());
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
        verify(plannerPickerPort, never()).pickForTask(any());
    }

    @Test
    @DisplayName("LLM 调用失败：记录 failed 事件并抛 BizException，不写回")
    void shouldRecordFailedEventWhenLlmFails() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("provider timeout", "error", "llm"));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("LLM 调用失败");
        verify(finalReportPersistService, never())
                .persistAndRequestReview(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_final_report_failed"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("token 超限降档重试：首档命中上下文上限后收紧截断重试并成功写回")
    void shouldDowngradeAndRetryOnTokenLimitError() {
        // 专测降档重试：关闭大纲规划，保持 2 次调用（首档 token 超限 → 降档成功）
        dispatchProperties.setAutoFinalReportOutlineEnabled(false);
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        // 产出超过第二档 2000 字符，确保重试 prompt 确实被收紧
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "X".repeat(9000))));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure(
                        "400 - Your request exceeded model token limit: 8192", "error", "llm"))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        // 共 2 次：①首档正文（token 超限）②降档重试成功
        verify(platformAgentExecutionService, org.mockito.Mockito.times(2))
                .executeSync(any(Agent.class), taskCaptor.capture());
        // 降档重试的 prompt 明显短于首档正文 prompt（截断从 8000 收紧到 2000）
        List<AgentTask> calls = taskCaptor.getAllValues();
        assertThat(calls.get(1).getUserPrompt().length())
                .isLessThan(calls.get(0).getUserPrompt().length());
        verify(finalReportPersistService).persistAndRequestReview(
                eq(TASK_ID), eq("# 整合报告"), eq(9L), any(), eq(1), eq(1), eq(true));
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
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure(
                        "400 - Your request exceeded model token limit: 8192", "error", "llm"));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("token limit");
        // 3C 后共 4 次：①出纲调用（失败→降级）②③④ 三档阶梯全部尝试后才失败
        verify(platformAgentExecutionService, org.mockito.Mockito.times(4))
                .executeSync(any(Agent.class), any(AgentTask.class));
        verify(finalReportPersistService, never())
                .persistAndRequestReview(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
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
        verify(plannerPickerPort, never()).pickForTask(any());
    }

    @Test
    @DisplayName("自动触发：已有报告时幂等跳过，不重复调 LLM")
    void shouldSkipAutoWhenReportAlreadyExists() {
        Task task = doneTask();
        task.setFinalReport("# 已有报告");
        when(taskService.getById(TASK_ID)).thenReturn(task);

        service.onTaskAutoCompleted(new TaskAutoCompletedEvent(TASK_ID));

        verify(plannerPickerPort, never()).pickForTask(any());
    }

    @Test
    @DisplayName("自动触发：生成异常被吞掉不外抛（手动端点兜底）")
    void shouldSwallowExceptionOnAutoGenerate() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        // 无子任务产出 → generate 内部抛 BizException，自动路径应吞掉

        service.onTaskAutoCompleted(new TaskAutoCompletedEvent(TASK_ID));

        verify(plannerPickerPort, never()).pickForTask(any());
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
        verify(plannerPickerPort, never()).pickForTask(any());
        verify(platformAgentExecutionService, never())
                .executeSync(any(Agent.class), any(AgentTask.class));
    }

    @Test
    @DisplayName("LLM 最终失败：状态置 FAILED（入口 CAS 一次 + 状态机迁移 GENERATING→FAILED）")
    void shouldMarkFailedStatusWhenLlmFails() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("provider timeout", "error", "llm"));

        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class);

        // 唯一一次直接 update = 入口 CAS 置 GENERATING；失败置 FAILED 改走状态机单点迁移
        verify(taskService, org.mockito.Mockito.times(1)).update(any());
        verify(taskService).transitFinalReportStatus(TASK_ID,
                FinalReportStatus.GENERATING, FinalReportStatus.FAILED);
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

        verify(plannerPickerPort, never()).pickForTask(any());
        verify(taskService, never()).update(any());
    }

    @Test
    @DisplayName("模板渲染断言：D2 悬空引用已内联 8 类清单，不含「见 eng-doc-standard」字样")
    void shouldRenderInlineLeakageChecklistWithoutExternalReference() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        String prompt = captureFinalPrompt();
        assertThat(prompt).contains("思维链泄漏 8 类速查")
                .contains("实现过程叙事")
                .contains("版本戳")
                .doesNotContain("eng-doc-standard");
    }

    @Test
    @DisplayName("读取口径：可直读文本附件优先于 displayText（附件正文入 prompt，output 兜底）")
    void shouldPreferAttachmentContentOverDisplayText() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "displayText 兜底文本")));
        List<Attachment> atts = new ArrayList<>();
        atts.add(textAttachment(201L, "detail.md", "附件完整正文内容"));
        when(attachmentService.listActive(11L)).thenReturn(atts);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        String prompt = captureFinalPrompt();
        assertThat(prompt).contains("detail.md").contains("附件完整正文内容");
        // 附件优先：displayText 不再作为正文注入
        assertThat(prompt).doesNotContain("displayText 兜底文本");
    }

    @Test
    @DisplayName("读取口径：findRecord 非空时注入执行摘要/交付物清单/关键决策")
    void shouldInjectExecutionRecordSummary() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(taskRunningSpecService.findRecord(TASK_ID, 11L)).thenReturn(
                ExecutionRecord.builder()
                        .subTaskId(11L)
                        .summary("完成调度链路梳理，确认异步边界")
                        .addDeliverable("调度链路图")
                        .addDeliverable("接口清单")
                        .addKeyDecision("采用异步触发")
                        .build());
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        String prompt = captureFinalPrompt();
        assertThat(prompt).contains("执行摘要：完成调度链路梳理，确认异步边界")
                .contains("交付物清单：调度链路图、接口清单")
                .contains("关键决策：采用异步触发");
    }

    @Test
    @DisplayName("多附件正文超生成层预算：块级截断保护上下文窗口，后续附件不注入")
    void shouldCapAttachmentLongTailBySectionLimit() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "兜底文本")));
        // 4 个附件各 9000 字符：附件层先按 AttachmentContentPolicy 预算处理（per-file 8000），
        // 拼接结果仍超生成层首档 8000 → 块级截断，后续附件正文不注入（降档优先降附件正文）
        // （先构造列表再 stub：避免 thenReturn 参数求值中嵌套 when() 触发 Mockito UnfinishedStubbing）
        List<Attachment> atts = new ArrayList<>();
        atts.add(textAttachment(201L, "a.md", "A".repeat(9000)));
        atts.add(textAttachment(202L, "b.md", "B".repeat(9000)));
        atts.add(textAttachment(203L, "c.md", "C".repeat(9000)));
        atts.add(textAttachment(204L, "d.md", "D".repeat(9000)));
        when(attachmentService.listActive(11L)).thenReturn(atts);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        String prompt = captureFinalPrompt();
        // 首个附件正文已注入（附件优先口径）
        assertThat(prompt).contains("#### 附件：a.md");
        // 无执行记录时超限标注 [TRUNCATED] 完整内容见附件
        assertThat(prompt).contains("[TRUNCATED] 完整内容见附件。");
        // 后续附件被生成层预算截断，不再逐文件注入（避免上下文爆炸）
        assertThat(prompt).doesNotContain("#### 附件：b.md");
        assertThat(prompt).doesNotContain("#### 附件：d.md");
    }

    @Test
    @DisplayName("块级截断：不拦腰切断表格行与代码行（围栏/行边界回退）")
    void shouldTruncateAtBlockBoundaryNotTableOrCode() {
        // 纯表格（无段落边界）：断在行首，最后一行完整
        String table = "| 列A | 列B |\n" + "| a1 | b1 |\n".repeat(1500);
        String tableCut = TaskFinalReportServiceImpl.truncateAtBlockBoundary(table, 8000);
        assertThat(tableCut.length()).isLessThan(8000);
        assertThat(tableCut.endsWith(" |")).isTrue();
        // 纯代码块（无段落）：limit 落在块内时回退到打开围栏前，输出不含未闭合围栏
        String code = "```java\n" + "code();\n".repeat(1000) + "```\n";
        String codeCut = TaskFinalReportServiceImpl.truncateAtBlockBoundary(code, 8000);
        assertThat(codeCut).doesNotContain("```");
        // 段落 + 代码块：段落边界优先（段落位置过浅时），回退到代码块打开围栏前
        String mixed = "先导段落说明。\n\n```sql\n" + "SELECT 1;\n".repeat(900);
        String mixedCut = TaskFinalReportServiceImpl.truncateAtBlockBoundary(mixed, 8000);
        assertThat(mixedCut).doesNotContain("SELECT 1;");
        assertThat(mixedCut).contains("先导段落说明。");
        assertThat(mixedCut).endsWith("\n\n");
    }

    @Test
    @DisplayName("3B 目标重写：模板含读者用途/主线论点/篇幅预算/覆盖追溯表/冲突必输，无旧铁律与 HTML 自检注释")
    void shouldRenderRewrittenGoalsWithoutLegacyClauses() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构梳理产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        String prompt = captureFinalPrompt();
        // 新增条款：读者与用途 / 主线论点 / 叙事预算 / 覆盖追溯表 / 冲突必输 / few-shot
        assertThat(prompt)
                .contains("读者与用途")
                .contains("主线论点")
                .contains("覆盖追溯表")
                .contains("叙事预算")
                .contains("不超过 3000 字")
                .contains("不超过 400 字")
                .contains("跨章去重")
                .contains("冲突矛盾必须输出")
                .contains("显式写「无」")
                .contains("few-shot");
        // 删除项：铁律2 严禁合并 / 章节数=总览表行数等式 / 末尾 HTML 自检注释
        assertThat(prompt)
                .doesNotContain("强制自检清单")
                .doesNotContain("HTML 注释")
                .doesNotContain("严禁将多个子任务合并")
                .doesNotContain("章节数必须等于总览表行数");
    }

    @Test
    @DisplayName("3C 大纲先行：出纲成功 → 正文按大纲章节分片渲染（2 次 LLM 调用）")
    void shouldPlanOutlineThenRenderByOutlineSections() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出"),
                doneSubTask(12L, "接口设计", "# 接口产出")));
        String outlineJson = "{\"mainTheses\":[\"结论：采用异步触发机制\"],"
                + "\"sections\":["
                + "{\"title\":\"接口契约\",\"subTaskRefs\":[\"#2\",\"#1\"]},"
                + "{\"title\":\"架构方案\",\"subTaskRefs\":[\"#1\"]}],"
                + "\"conflicts\":[\"超时阈值口径不一致\"]}";
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(outlineJson, "stop", "llm", 10),
                        AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
        List<AgentTask> calls = taskCaptor.getAllValues();
        // 第 1 次：出纲调用（outline 模板，含子任务编号输入）
        assertThat(calls.get(0).getUserPrompt())
                .contains("归并出纲师")
                .contains("### #2 接口设计");
        // 第 2 次：正文渲染——注入规划产物（主线论点/覆盖追溯表/矛盾清单）并按章节分片
        String prompt = calls.get(1).getUserPrompt();
        assertThat(prompt)
                .contains("主线论点（执行摘要必须以此为准）")
                .contains("- 结论：采用异步触发机制")
                .contains("覆盖追溯表")
                .contains("| §1 | 接口契约 | #2、#1 |")
                .contains("规划阶段矛盾清单（第4步差异与冲突澄清必须逐条覆盖）")
                .contains("### 章节 1：接口契约（大纲归并主题）")
                .contains("### 章节 2：架构方案（大纲归并主题）");
        // 章节顺序 = 大纲顺序（读者理解顺序，非子任务序号）
        assertThat(prompt.indexOf("### 章节 1：接口契约"))
                .isLessThan(prompt.indexOf("### 章节 2：架构方案"));
        // 一对多：架构梳理（#1）被两个章节引用，产出块跟随各自章节分片注入
        int first = prompt.indexOf("子任务 1：架构梳理");
        assertThat(first).isGreaterThan(prompt.indexOf("### 章节 1：接口契约"))
                .isLessThan(prompt.indexOf("### 章节 2：架构方案"));
    }

    @Test
    @DisplayName("3C 大纲调用失败：降级为单次调用生成（主链不受影响）")
    void shouldFallbackToSingleCallWhenOutlineFails() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("outline provider timeout", "error", "llm"),
                        AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
        // 第 2 次（正文渲染）走单次调用兜底：不含大纲注入段，产出按拓扑序拼接
        String prompt = taskCaptor.getAllValues().get(1).getUserPrompt();
        assertThat(prompt).doesNotContain("主线论点（执行摘要必须以此为准）")
                .contains("### 子任务 1：架构梳理");
    }

    @Test
    @DisplayName("3C 大纲解析失败（非 JSON 输出）：降级为单次调用生成")
    void shouldFallbackWhenOutlineParseFails() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("这不是 JSON，只是普通文本", "stop", "llm", 10),
                        AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
        assertThat(taskCaptor.getAllValues().get(1).getUserPrompt())
                .doesNotContain("主线论点（执行摘要必须以此为准）");
    }

    @Test
    @DisplayName("3C 开关关闭：不调用出纲，整体单次调用")
    void shouldSkipOutlineWhenDisabled() {
        dispatchProperties.setAutoFinalReportOutlineEnabled(false);
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        verify(platformAgentExecutionService, times(1))
                .executeSync(any(Agent.class), any(AgentTask.class));
    }

    @Test
    @DisplayName("3A 写回透传：generate 以 attempt=1 + 子任务数触发写回，rework 追加 attempt=2")
    void shouldPublishGeneratedEventWithAttempt() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<String> reportCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> attemptCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> sectionCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(finalReportPersistService, times(1)).persistAndRequestReview(
                eq(TASK_ID), reportCaptor.capture(), eq(9L), any(),
                attemptCaptor.capture(), sectionCaptor.capture(), eq(true));
        assertThat(reportCaptor.getValue()).isEqualTo("# 整合报告");
        assertThat(attemptCaptor.getValue()).isEqualTo(1);
        assertThat(sectionCaptor.getValue()).isEqualTo(1);

        service.rework(TASK_ID, "覆盖追溯表不完整", 2);

        assertThat(capturePersistAttempts(2)).containsExactly(1, 2);
    }

    @Test
    @DisplayName("3A 去状态化：同轮返工轮次由监听器显式传入，写回原样透传（attempt=3）")
    void shouldPropagateExplicitAttemptFromRework() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        // 监听器持 event.getAttempt()=2，同轮返工显式传 3（2 + 1）；无计数器可依赖
        service.rework(TASK_ID, "覆盖追溯表不完整", 3);

        assertThat(capturePersistAttempts(1)).containsExactly(3);
    }

    @Test
    @DisplayName("3A 轮次隔离：连续手动「重新生成」每次都是 attempt=1（与首次生成同权）")
    void shouldResetAttemptOnManualRegenerate() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);
        service.generate(TASK_ID);
        service.generate(TASK_ID);

        // 复位语义：手动重新生成不继承上一轮轮次，审查返工额度与首次生成一致
        assertThat(capturePersistAttempts(3)).containsExactly(1, 1, 1);
    }

    @Test
    @DisplayName("3A 轮次隔离：同轮 rework 累加、手动重新生成复位（1 → 2 → 1）")
    void shouldAccumulateWithinCycleAndResetOnRegenerate() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);
        service.rework(TASK_ID, "覆盖追溯表不完整", 2);
        service.generate(TASK_ID);

        // 去状态化：同轮返工显式传 2（监听器 1+1），跨轮 generate 恒为 1（无历史轮次残留）
        assertThat(capturePersistAttempts(3)).containsExactly(1, 2, 1);
    }

    @Test
    @DisplayName("3A 轮次隔离：CAS 防重入拒绝的点击不消耗轮次（后续生成仍为 attempt=1）")
    void shouldNotConsumeAttemptWhenCasRejected() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        // 第一次点击：CAS 拒绝（另一条路径正在生成）
        when(taskService.update(any())).thenReturn(false);
        assertThatThrownBy(() -> service.generate(TASK_ID))
                .isInstanceOf(BizException.class);
        // 第二次点击：CAS 通过 → 计数未因被拒的点击被消耗
        when(taskService.update(any())).thenReturn(true);
        service.generate(TASK_ID);

        assertThat(capturePersistAttempts(1)).containsExactly(1);
    }

    @Test
    @DisplayName("3A 返工重写：重写 prompt 注入上轮审查驳回意见（首次生成为空）")
    void shouldInjectReviewFeedbackIntoReworkPrompt() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);
        // 首次生成：驳回意见为空，不注入
        assertThat(captureFinalPrompt()).doesNotContain("驳回意见A");

        service.rework(TASK_ID, "驳回意见A：覆盖追溯表须补子任务2");

        String rewrotePrompt = captureFinalPrompt();
        assertThat(rewrotePrompt).contains("上轮审查驳回意见")
                .contains("驳回意见A：覆盖追溯表须补子任务2");
    }

    @Test
    @DisplayName("3C 分片预算：每章独立，超长章块级截断不吞并其余章完整注入")
    void shouldApplyPerChapterBudgetInOutlineSharding() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "X".repeat(9000)),
                doneSubTask(12L, "接口设计", "YYYY")));
        String outlineJson = "{\"mainTheses\":[],"
                + "\"sections\":["
                + "{\"title\":\"接口规范\",\"subTaskRefs\":[\"#1\"]},"
                + "{\"title\":\"架构方案\",\"subTaskRefs\":[\"#2\"]}],"
                + "\"conflicts\":[]}";
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(outlineJson, "stop", "llm", 10),
                        AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
        String prompt = taskCaptor.getAllValues().get(1).getUserPrompt();
        // 章 1 正文 9000 字符超每章预算 4000（首档 8000/2 章）→ 块级截断标注
        assertThat(prompt).contains("[TRUNCATED] 完整内容见附件。");
        // 章 2 短正文完整注入，不受章 1 超长影响（每章预算独立）
        assertThat(prompt).contains("### 章节 2：架构方案（大纲归并主题）")
                .contains("YYYY");
    }

    @Test
    @DisplayName("超限标注：有执行记录标 [SUMMARIZED]，无记录标 [TRUNCATED] 完整内容见附件")
    void shouldAnnotateSummarizedOrTruncatedBasedOnRecord() {
        // 有记录：正文超长时映射 [SUMMARIZED]
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "X".repeat(9000)),
                doneSubTask(12L, "链路设计", "Y".repeat(9000))));
        when(taskRunningSpecService.findRecord(TASK_ID, 11L)).thenReturn(
                ExecutionRecord.builder().subTaskId(11L).summary("摘要A").build());
        // 12L 无记录
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        String prompt = captureFinalPrompt();
        assertThat(prompt).contains("[SUMMARIZED] 产出正文超长已块级截断")
                .contains("[TRUNCATED] 完整内容见附件。");
        // 旧措辞已删除：不再出现「以已提供部分为准」
        assertThat(prompt).doesNotContain("以已提供部分为准");
    }

    // ══════════════════════════════════════════════════════════
    // §12.1 单槽列回滚（prev 槽 + rollback 端点）
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("§12.1 写回委托：报告写回（含 prev 槽换入）整体交给 FinalReportPersistService 事务边界")
    void shouldWritePrevSlotOnGenerate() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());
        when(plannerPickerPort.pickForTask(TASK_ID)).thenReturn(planner());
        when(subTaskQueryChain.list()).thenReturn(List.of(
                doneSubTask(11L, "架构梳理", "# 架构产出")));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("# 整合报告", "stop", "llm", 10));

        service.generate(TASK_ID);

        // prev 落槽的 setSql 已随写回一并迁入事务边界 Bean（由 FinalReportPersistServiceTest
        // 覆盖）；本类只断言委托发生，且不再自行拼 task 表更新语句
        verify(finalReportPersistService).persistAndRequestReview(
                eq(TASK_ID), eq("# 整合报告"), eq(9L), any(), eq(1), eq(1), eq(true));
        verify(taskService, never()).lambdaUpdate();
    }

    @Test
    @DisplayName("§12.1 回滚：current 与 prev 槽整体互换（含 Agent 与时间戳）并落 rolled_back 事件")
    void shouldRollbackSwapCurrentAndPrev() {
        Task task = doneTask();
        task.setFinalReport("V2新版正文");
        task.setFinalReportAgentId(9L);
        task.setFinalReportTime(OffsetDateTime.parse("2026-09-28T10:00:00+08:00"));
        task.setFinalReportPrev("V1旧版正文");
        task.setFinalReportPrevAgentId(3L);
        task.setFinalReportPrevTime(OffsetDateTime.parse("2026-09-28T09:00:00+08:00"));
        when(taskService.getById(TASK_ID)).thenReturn(task);

        service.rollback(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Task>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(taskService).update(wrapperCaptor.capture());
        LambdaUpdateWrapper<Task> wrapper = wrapperCaptor.getValue();
        // 互换语义：current 得旧 prev（V1 正文 + Agent3 + 09:00），prev 得旧 current（V2 正文 + Agent9 + 10:00）
        assertThat(wrapper.getSqlSet()).contains("final_report")
                .contains("final_report_prev")
                .contains("final_report_prev_agent_id")
                .contains("final_report_prev_time");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("V1旧版正文", 3L, task.getFinalReportPrevTime())
                .contains("V2新版正文", 9L, task.getFinalReportTime())
                .contains(FinalReportStatus.DONE);
        // 落 rolled_back 事件，actor 为被恢复到 current 的上一版生成者（Agent3）
        verify(taskTimelineService).recordEvent(eq(TASK_ID), isNull(),
                eq("task_final_report_rolled_back"), eq(AgentRole.PLANNER), eq(3L), anyMap());
    }

    @Test
    @DisplayName("§12.1 回滚：无上一版抛 409")
    void shouldRejectRollbackWithoutPrev() {
        when(taskService.getById(TASK_ID)).thenReturn(doneTask());

        assertThatThrownBy(() -> service.rollback(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有可恢复的上一版");
        verify(taskTimelineService, never())
                .recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("§12.1 回滚：生成在途（快照 GENERATING + CAS 拒绝）抛 409 而非状态机异常")
    void shouldRejectRollbackWhenGenerating() {
        Task task = doneTask();
        task.setFinalReport("V2新版正文");
        task.setFinalReportPrev("V1旧版正文");
        task.setFinalReportStatus(FinalReportStatus.GENERATING);
        when(taskService.getById(TASK_ID)).thenReturn(task);
        when(taskService.update(any())).thenReturn(false);

        assertThatThrownBy(() -> service.rollback(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("正在生成中");
        verify(taskTimelineService, never())
                .recordEvent(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("§12.1 回滚：任务不存在抛 404")
    void shouldThrowWhenRollbackTaskMissing() {
        when(taskService.getById(TASK_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.rollback(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("任务不存在");
    }
}
