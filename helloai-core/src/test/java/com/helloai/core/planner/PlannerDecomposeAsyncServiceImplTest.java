package com.helloai.core.planner;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
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
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.policy.RequirementPackageParser;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.impl.PlannerDecomposeAsyncServiceImpl;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.port.UncertaintyDraft;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
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
 * validateDependencies 依赖环校验 / G-011 需求包渲染与 uncertainties 管理。
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

    private PlannerDecomposeAsyncServiceImpl asyncService;


    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        asyncService = new PlannerDecomposeAsyncServiceImpl(
                taskService, subTaskService, plannerAgentPicker,
                platformAgentExecutionService, taskTimelineService,
                agentService, agentSkillSpecService, contextSnapshotRenderer, new ObjectMapper());

    }

    private TaskView planningTask() {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                TaskStatus.PLANNING, null, null, null, null, null, null, null);
    }

    /** 任务快照（不可变，等价原 setStatus/setPriority 写法）。 */
    private TaskView taskView(TaskStatus status, String priority) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                status, null, priority, null, null, null, null, null);
    }

    /** 重加载草案快照（含优先级与 context，等价原 setPriority/setContext 写法）。 */
    private SubTaskView draftFull(long id, String priority, Map<String, Object> context) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null,
                null, null, null, null, null, null, context, List.of(), null, null, priority, null, null,
                null, null, null, null, null, null, null);
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

    private SubTaskView draft(long id) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null,
                null, null, null, null, null, null, List.of());
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
        SubTaskView reloaded = draftWithPriority(11L, "HIGH");
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(reloaded));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).priority()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("正常拆解：markdown fence 容错解析，草案落库 PENDING_PLAN_REVIEW，start/end/generated timeline 齐全")
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
        SubTaskView reloaded1 = draftFull(11L, "HIGH", Map.of("plannerAgentId", 9L));
        SubTaskView reloaded2 = draftFull(12L, "MEDIUM", Map.of("plannerAgentId", 9L));
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(reloaded1, reloaded2));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
        List<SubTaskDraft> drafts = captor.getValue();
        assertThat(drafts).hasSize(2);
        assertThat(drafts).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(SubTaskStatus.PENDING_PLAN_REVIEW);
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
    @DisplayName("task_plan_llm_call_end 事件携带耗时毫秒、finishReason、tokenUsage")
    void shouldRecordLlmCallEndWithObservabilityFields() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"第一步","content":"c","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(
                draft(11L), draft(12L), draft(13L), draft(14L)));
        when(subTaskService.listViewsByIds(List.of(11L))).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
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

        verify(subTaskService, never()).saveDrafts(any());
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  失败路径：内部闭环回退 PENDING（不再抛出）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1 必填兜底：草案缺 acceptance（空白占位）→ fail-close 回退 PENDING + 定位审计")
    void shouldRollbackWhenDraftMissingAcceptance() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [
                          {"title":"完整条目","content":"内容","deliverable":"交付物产物完整清单","acceptance":"运行 X 命令输出预期 Y"},
                          {"title":"残缺条目","content":"内容","deliverable":"交付物产物完整清单","acceptance":"   "}
                        ]
                        """, "stop", "llm", 100));

        asyncService.executeDecompose(TASK_ID);

        // 整批拒绝：不落库任何草案（残缺草案比拆解失败更贵——执行/审查侧将失去验收依据）
        verify(subTaskService, never()).saveDrafts(any());
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
        // 审计先于抛出：draftSeq / field 精确指向第 2 条 acceptance
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_draft_field_missing"),
                eq(AgentRole.PLANNER), eq(9L),
                argThat(payload -> "acceptance".equals(payload.get("field"))
                        && Integer.valueOf(2).equals(payload.get("draftSeq"))));
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
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
        when(subTaskService.saveDrafts(any())).thenReturn(List.of());
        lenient().when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(new ArrayList<>());

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

        verify(subTaskService, never()).saveDrafts(any());
        // 失败回退走 CAS（lambdaUpdate）
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
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

        verify(subTaskService, never()).saveDrafts(any());
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
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

        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
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
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 第 2 条依赖第 1 条（序号 1 → 重加载 drafts 的 id=11L）
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        ```json
                        [
                          {"title":"第一步","content":"准备","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","dependsOn":[]},
                          {"title":"第二步","content":"执行","deliverable":"交付物说明文档清单","acceptance":"验收判据说明文字内容","dependsOn":[1]}
                        ]
                        ```
                        """, "stop", "llm", 100));
        SubTaskView reloaded1 = draft(11L);
        SubTaskView reloaded2 = draft(12L);
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(reloaded1, reloaded2));
        // 幽灵场景：依赖 ID 11 在所有草案之外，listByIds 查不到
        when(subTaskService.listViewsByIds(List.of(11L))).thenReturn(List.of());

        asyncService.executeDecompose(TASK_ID);

        // 幽灵依赖不得静默落库：任何依赖回写都不执行
        verify(subTaskService, never()).updateDependsOn(anyLong(), any());
        // 拆解失败回退 PENDING + task_plan_failed
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("§6.100: 依赖回写目标全部存在时正常落库（序号→真实 id 映射）")
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
        SubTaskView reloaded1 = draft(11L);
        SubTaskView reloaded2 = draft(12L);
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(reloaded1, reloaded2));
        when(subTaskService.listViewsByIds(List.of(11L))).thenReturn(List.of(reloaded1));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
        assertThat(captor.getValue().get(0).context())
                .doesNotContainKey(SubTaskContextKeys.ACCEPTANCE_PRECONDITIONS);
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_acceptance_precondition_unmet"),
                any(), any(), anyMap());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService).saveDrafts(any());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService).saveDrafts(any());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        // 告警而非阻断：草案仍落库
        verify(subTaskService).saveDrafts(any());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_skill_unmatched"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    // ══════════════════════════════════════════════════════════════
    //  TD-5 接线：acceptanceKind 显式声明判定 + 分流校验
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("TD-5 接线：acceptanceKind 缺失（回落 AUTOMATED）且文本普通 → Warn 放行，草案照常落库")
    void shouldWarnAndPersistWhenKindMissing() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // acceptanceKind 字段缺失 → parse 回落 AUTOMATED；文本「评审通过」无命令/断言 → 应走 Warn 而非 Fail
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"设计表结构","content":"建表","deliverable":"DDL 建表脚本","acceptance":"评审通过且无语法错误"},
                         {"title":"实现统计接口","content":"实现","deliverable":"接口代码实现文件","acceptance":"接口返回 200 状态码"}]
                        """, "stop", "llm", 100));
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L), draft(12L)));

        asyncService.executeDecompose(TASK_ID);

        // 不阻断：草案仍落库
        verify(subTaskService).saveDrafts(any());
        // Warn 路径：缺失 kind 的「评审通过」记一条审计 hint（不抛异常）
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_acceptance_kind_mismatch"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("TD-5 接线：显式声明 AUTOMATED 但文本不含命令/预期结果 → fail-close 整批拒绝，不落库")
    void shouldRejectWhenExplicitAutomatedMismatch() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 显式 AUTOMATED + 空话「质量良好」→ Fail（fail-close）
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"写文档","content":"c","deliverable":"d","acceptance":"质量良好","acceptanceKind":"AUTOMATED"}]
                        """, "stop", "llm", 100));

        asyncService.executeDecompose(TASK_ID);

        // fail-close：saveDrafts 绝不调用；拆解失败回退 PENDING + task_plan_failed
        verify(subTaskService, never()).saveDrafts(any());
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("TD-5 接线：显式声明 STRUCTURAL + 可数量词 → 通过，草案落库")
    void shouldPassWhenExplicitStructuralCountable() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"产出报告","content":"c","deliverable":"研究报告成品文档","acceptance":"不少于 5 章完整内容","acceptanceKind":"STRUCTURAL"}]
                        """, "stop", "llm", 100));
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService).saveDrafts(any());
        // 显式声明合法 → 无 mismatch 审计
        verify(taskTimelineService, never()).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_acceptance_kind_mismatch"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("TD-5 长度闸：acceptance/deliverable 低于字符下限 → fail-close 整批拒绝，不落库")
    void shouldRejectWhenFieldLengthBelowFloor() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // 各字段非空（过必填校验）但 deliverable=3 字符 < 8 下限 → 长度闸拒绝
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class))).thenReturn(
                AgentResult.success("""
                        [{"title":"产出物","content":"c","deliverable":"报告","acceptance":"验收判据说明文字内容"}]
                        """, "stop", "llm", 100));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService, never()).saveDrafts(any());
        verify(taskService).casStatus(TASK_ID, TaskStatus.PLANNING, TaskStatus.PENDING);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_failed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
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
        when(subTaskService.listDraftsInInsertOrder(anyLong(), any())).thenReturn(List.of(draft(11L)));

        asyncService.executeDecompose(TASK_ID);

        verify(subTaskService).saveDrafts(any());
    }

    // ══════════════════════════════════════════════════════════════
    //  `TD-9` 单条草案 expand / regenerate
    //  ══════════════════════════════════════════════════════════════

    private static final Long ORIGINAL_DRAFT_ID = 55L;

    /** `TD-9` 单条草案视图（四要素 + 优先级 + 依赖齐全，PENDING_PLAN_REVIEW）。 */
    /** `TD-9` 单条草案视图（四要素 + 优先级 + 依赖齐全，PENDING_PLAN_REVIEW；createTime 非 null）。 */
    private SubTaskView draftFullTD9(long id, String title, String content, String deliverable,
                                     String acceptance, List<Long> dependsOn) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null,
                title, content, deliverable, acceptance, null, null, null, List.of(), dependsOn,
                null, "MEDIUM", null, List.of(), null, null, null, null, null,
                OffsetDateTime.now(), null);
    }

    /** expand 共享打桩：原草案 + 任务 + Planner + LLM 输出数组 + 重加载新增两条。 */
    private void stubExpandContext(String llmOutput) {
        when(subTaskService.getView(ORIGINAL_DRAFT_ID)).thenReturn(
                draftFullTD9(ORIGINAL_DRAFT_ID, "原草案标题", "原草案内容", "原交付物产物",
                        "运行 X 命令输出预期 Y", List.of()));
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(llmOutput, "stop", "llm", 100));
        // 技能目录空（renderSkillCatalog 降级文案）；快照渲染默认 null（renderSnapshotSafely 降级）
        when(agentSkillSpecService.listPackages()).thenReturn(List.of());
    }

    @Test
    @DisplayName("TD-9 expand：组内依赖 Kahn 通过、新增落库、依赖回写、原草案逻辑删")
    void shouldExpandDraftWithInGroupDependencies() {
        stubExpandContext("""
                [
                  {"title":"子步骤A","content":"先准备","deliverable":"准备产物完整清单","acceptance":"运行 X 命令输出预期 Y","dependsOn":[]},
                  {"title":"子步骤B","content":"后执行","deliverable":"执行产物完整清单","acceptance":"运行 X 命令输出预期 Y","dependsOn":[1]}
                ]
                """);
        // saveDrafts 返回新增（带真实 id）；重加载同样返回这两条（原草案随后逻辑删）
        SubTaskView new1 = draftFullTD9(81L, "子步骤A", "先准备", "准备产物完整清单",
                "运行 X 命令输出预期 Y", null);
        SubTaskView new2 = draftFullTD9(82L, "子步骤B", "后执行", "执行产物完整清单",
                "运行 X 命令输出预期 Y", null);
        when(subTaskService.saveDrafts(any())).thenReturn(List.of(new1, new2));
        when(subTaskService.listDraftsInInsertOrder(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(List.of(new1, new2));
        // 幽灵依赖防御：依赖回写前校验目标真实存在（new1 存在）
        when(subTaskService.listViewsByIds(List.of(81L))).thenReturn(List.of(new1));

        List<SubTaskView> created = asyncService.expandById(ORIGINAL_DRAFT_ID);

        // ① 新增落库（PENDING_PLAN_REVIEW，FINE 粒度由实现固定）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SubTaskDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(subTaskService).saveDrafts(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .allSatisfy(d -> assertThat(d.status()).isEqualTo(SubTaskStatus.PENDING_PLAN_REVIEW));
        // ② 组内依赖回写：第 2 条依赖第 1 条真实 id
        verify(subTaskService).updateDependsOn(82L, List.of(81L));
        // ③ 原草案逻辑删（审计保留）
        verify(subTaskService).removeById(ORIGINAL_DRAFT_ID);
        // ④ 返回新增两条
        assertThat(created).extracting(SubTaskView::id).containsExactly(81L, 82L);
        // ⑤ timeline 记录展开事件
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(ORIGINAL_DRAFT_ID), eq("task_plan_draft_expanded"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }

    @Test
    @DisplayName("TD-9 expand：被其他草案依赖 → 拒绝展开（幽灵依赖 fail-close），不调 LLM")
    void shouldRejectExpandWhenReferencedByOtherDraft() {
        when(subTaskService.getView(ORIGINAL_DRAFT_ID)).thenReturn(
                draftFullTD9(ORIGINAL_DRAFT_ID, "原草案", "内容", "交付物产物完整清单",
                        "运行 X 命令输出预期 Y", List.of()));
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        // 兄弟草案 dependsOn 含原草案 id
        when(subTaskService.listDraftsInInsertOrder(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(List.of(draftFullTD9(99L, "兄弟草案", "内容", "交付物产物完整清单",
                        "运行 X 命令输出预期 Y", List.of(ORIGINAL_DRAFT_ID))));

        assertThatThrownBy(() -> asyncService.expandById(ORIGINAL_DRAFT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("被其他草案依赖");

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService, never()).saveDrafts(any());
        verify(subTaskService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("TD-9 expand：非 PENDING_PLAN_REVIEW 状态 → 拒绝，不调 LLM")
    void shouldRejectExpandWhenNotDraftStatus() {
        when(subTaskService.getView(ORIGINAL_DRAFT_ID)).thenReturn(
                new SubTaskView(ORIGINAL_DRAFT_ID, TASK_ID, SubTaskStatus.ASSIGNED, null, null,
                        "已转正", "内容", "交付物产物完整清单", "运行 X 命令输出预期 Y",
                        null, null, null, List.of(), null, null, "MEDIUM", null, List.of(),
                        null, null, null, null, null, null, null));

        assertThatThrownBy(() -> asyncService.expandById(ORIGINAL_DRAFT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅草案待审状态可展开");

        verify(platformAgentExecutionService, never()).executeSync(anyLong(), any(AgentTask.class));
        verify(subTaskService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("TD-9 expand：LLM 调用失败 → 事务不落库、原草案不被破坏（removeById 绝不调用）")
    void shouldNotTouchOriginalWhenExpandLlmFails() {
        when(subTaskService.getView(ORIGINAL_DRAFT_ID)).thenReturn(
                draftFullTD9(ORIGINAL_DRAFT_ID, "原草案标题", "原草案内容", "原交付物产物",
                        "运行 X 命令输出预期 Y", List.of()));
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("provider timeout", "error", "llm"));

        assertThatThrownBy(() -> asyncService.expandById(ORIGINAL_DRAFT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Planner LLM 调用失败");

        verify(subTaskService, never()).saveDrafts(any());
        verify(subTaskService, never()).removeById(anyLong());
        verify(subTaskService, never()).updateDependsOn(anyLong(), any());
    }

    @Test
    @DisplayName("TD-9 regenerate：title/id/dependsOn 不变量白名单——LLM 改了也忽略，四要素落库")
    void shouldRegenerateOnlyFourElements() {
        SubTaskView original = draftFullTD9(ORIGINAL_DRAFT_ID, "原标题", "原内容", "原交付物产物",
                "原验收判据内容", List.of(20L));
        SubTaskView updated = draftFullTD9(ORIGINAL_DRAFT_ID, "原标题", "新内容", "新交付物产物完整清单",
                "不少于 5 章完整内容且章节编号连续", List.of(20L));
        // 第 1 次 getView 取原草案，第 2 次（落库后）取更新后的草案
        when(subTaskService.getView(ORIGINAL_DRAFT_ID)).thenReturn(original, updated);
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(plannerAgentPicker.pickForTask(TASK_ID)).thenReturn(llmPlanner());
        // LLM 恶意改 title / dependsOn / priority —— 服务端必须忽略，只取四要素
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success("""
                        {"title":"LLM 想改标题","content":"新内容","deliverable":"新交付物产物完整清单",
                         "acceptance":"不少于 5 章完整内容且章节编号连续","acceptanceKind":"STRUCTURAL",
                         "priority":"LOW","dependsOn":[999]}
                        """, "stop", "llm", 100));
        when(agentSkillSpecService.listPackages()).thenReturn(List.of());

        SubTaskView result = asyncService.regenerateById(ORIGINAL_DRAFT_ID);

        // ① updateDraftContent 只收到四要素：content/deliverable/acceptance/acceptanceKind
        verify(subTaskService).updateDraftContent(eq(ORIGINAL_DRAFT_ID),
                eq("新内容"), eq("新交付物产物完整清单"), eq("不少于 5 章完整内容且章节编号连续"), eq("STRUCTURAL"));
        // ② 不变量：返回快照 title / id / dependsOn 保持原值
        assertThat(result.id()).isEqualTo(ORIGINAL_DRAFT_ID);
        assertThat(result.title()).isEqualTo("原标题");
        assertThat(result.dependsOn()).containsExactly(20L);
        // ③ timeline 记录重生成事件
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), eq(ORIGINAL_DRAFT_ID), eq("task_plan_draft_regenerated"),
                eq(AgentRole.PLANNER), eq(9L), anyMap());
    }
}

