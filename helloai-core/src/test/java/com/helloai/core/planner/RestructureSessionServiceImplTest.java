package com.helloai.core.planner;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AcceptanceKind;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskPriority;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.service.impl.RestructureSessionServiceImpl;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskDerivationService;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("执行期重构派生会话（ADR-008 §16 四段上下文）")
class RestructureSessionServiceImplTest {

    private static final long SUB_TASK_ID = 5L;
    private static final long TASK_ID = 9L;
    private static final long CONVERSATION_ID = 300L;

    @Mock private SubTaskService subTaskService;
    @Mock private TaskService taskService;
    @Mock private TaskDerivationService taskDerivationService;
    @Mock private RequirementConversationService conversationService;
    @Mock private RequirementMessageService messageService;
    @Mock private PlanDraftService planDraftService;

    private RestructureSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RestructureSessionServiceImpl(subTaskService, taskService, taskDerivationService,
                conversationService, messageService, planDraftService);
    }

    /** 父任务 X：只有 context 是断言关注点，其余字段走兼容构造的 null。 */
    private static TaskView taskWithContext(Map<String, Object> context) {
        return new TaskView(TASK_ID, "父任务", "d", null, null, null, null, null,
                TaskStatus.IN_PROGRESS, null, "HIGH", context, List.of(), null, null, null);
    }

    private static TaskView taskView(TaskStatus status, Map<String, Object> context) {
        return new TaskView(TASK_ID, "父任务", "d", null, null, null, null, null,
                status, null, "HIGH", context, List.of(), null, null, null);
    }

    private static PlanDraftView draft(long id, String code, String title, String content,
                                       String acceptance, List<String> dependsOnCodes) {
        return new PlanDraftView(id, TASK_ID, code, 1, 1, title, content, "交付-" + code,
                acceptance, AcceptanceKind.AUTOMATED, TaskPriority.HIGH, 1, List.of(),
                "约束-" + code, List.of(), dependsOnCodes, Map.of(), null);
    }

    private static SubTaskView view(long id, String title, String content, String acceptance,
                                    Map<String, Object> context, List<Long> dependsOn, SubTaskStatus status) {
        return new SubTaskView(id, TASK_ID, status, null, 2, title, content, "交付物-" + id, acceptance, null, "约束-" + id, context, List.of(), dependsOn, null, "HIGH", 0, List.of(), null, Map.of(), null, null, 0, null, null, null);
    }

    @Test
    @DisplayName("判据下沉：assertDerivable 拒绝时 400 冒泡，不建会话")
    void shouldPropagateAssertDerivableRejection() {
        when(subTaskService.getView(SUB_TASK_ID))
                .thenReturn(view(SUB_TASK_ID, "写报告", "c", "a", Map.of(), List.of(), SubTaskStatus.PENDING));
        // 状态判据已下沉到 task 域（AWAITING_DERIVED 要看事实，不能在本类判），
        // 本类只负责把拒绝原因原样冒泡，不吞异常、不建会话。
        doThrow(new BizException(400, "仅重构中状态可派生独立任务，当前状态: PENDING"))
                .when(taskDerivationService).assertDerivable(SUB_TASK_ID);

        assertThatThrownBy(() -> service.startSession(SUB_TASK_ID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅重构中状态可派生独立任务");
        verify(conversationService, never()).save(any());
        verify(messageService, never()).addMessage(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("已有 ACTIVE 派生会话 → 幂等返回原 id，不重复建")
    void shouldReuseExistingActiveSession() {
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(
                view(SUB_TASK_ID, "写报告", "c", "a", Map.of(), List.of(), SubTaskStatus.RESTRUCTURING));
        RequirementConversation existing = new RequirementConversation();
        existing.setId(CONVERSATION_ID);
        when(conversationService.findActiveBySourceSubTask(SUB_TASK_ID)).thenReturn(existing);

        assertThat(service.startSession(SUB_TASK_ID, null)).isEqualTo(CONVERSATION_ID);
        verify(conversationService, never()).save(any());
        verify(messageService, never()).addMessage(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("新建会话：挂父任务与来源子任务，首条消息含四段上下文与上下游内容")
    void shouldCreateSessionWithFourSegments() {
        SubTaskView target = view(SUB_TASK_ID, "写周报", "写一份周报", "含三部分", 
                Map.of("blockReason", "接口不可用"), List.of(11L), SubTaskStatus.RESTRUCTURING);
        SubTaskView upstream = view(11L, "收集素材", "收集本周素材", "素材齐全",
                Map.of("lastExecution", Map.of("output", "上游产出：三条完成事项")), List.of(), SubTaskStatus.DONE);
        SubTaskView downstream = view(12L, "汇总成稿", "基于周报汇总", "引用周报结论",
                Map.of(), List.of(SUB_TASK_ID), SubTaskStatus.PENDING);
        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(target);
        when(subTaskService.listViewsByTaskId(TASK_ID)).thenReturn(List.of(target, upstream, downstream));
        when(conversationService.findActiveBySourceSubTask(SUB_TASK_ID)).thenReturn(null);
        doAnswer(inv -> {
            ((RequirementConversation) inv.getArgument(0)).setId(CONVERSATION_ID);
            return true;
        }).when(conversationService).save(any(RequirementConversation.class));

        Long id = service.startSession(SUB_TASK_ID, 88L);

        assertThat(id).isEqualTo(CONVERSATION_ID);
        ArgumentCaptor<RequirementConversation> conv = ArgumentCaptor.forClass(RequirementConversation.class);
        verify(conversationService).save(conv.capture());
        assertThat(conv.getValue().getTaskId()).isEqualTo(TASK_ID);
        assertThat(conv.getValue().getSourceSubTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(conv.getValue().getTitle()).startsWith("重构派生：写周报");

        ArgumentCaptor<String> seed = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONVERSATION_ID), eq("user"), seed.capture());
        assertThat(seed.getValue())
                .contains("① 子任务原要求", "写一份周报", "含三部分")
                .contains("② 失败/阻塞轨迹", "接口不可用")
                .contains("③ 上游已完成产出", "上游产出：三条完成事项")
                .contains("④ 下游依赖期待", "汇总成稿", "引用周报结论");
    }

    @Test
    @DisplayName("第五段：父任务声明的执行环境原样进种子（路径/基线/运行时/数据源）")
    void shouldEmbedExecutionEnvFromParentTask() {
        stubSessionCreation();
        when(taskService.getView(TASK_ID)).thenReturn(taskWithContext(Map.of("executionEnv", Map.of(
                "repo", Map.of("kind", "local", "value", "E:/yhzx/1027/helloai", "baseRef", "main"),
                "runtime", List.of("jdk17", "bash"),
                "datasources", List.of(Map.of("type", "postgresql",
                        "url", "localhost:15432/helloai", "ready", true))))));

        service.startSession(SUB_TASK_ID, null);

        assertThat(capturedSeed())
                .as("二次拆解看不到环境，模型就会把「仓库中」当已知事实写进终稿，"
                        + "派生任务 Y 再拆解时又回到「仓库在哪」的猜测")
                .contains("⑤ 执行环境声明")
                .contains("E:/yhzx/1027/helloai", "main", "jdk17、bash",
                        "postgresql", "localhost:15432/helloai", "已就绪");
    }

    @Test
    @DisplayName("第五段：父任务未声明 → 显式写「未声明」，不留空白让模型自由假设")
    void shouldMarkUndeclaredEnv() {
        stubSessionCreation();
        when(taskService.getView(TASK_ID)).thenReturn(taskWithContext(Map.of("conversationId", 1L)));

        service.startSession(SUB_TASK_ID, null);

        assertThat(capturedSeed())
                .contains("⑤ 执行环境声明", "未声明执行环境")
                .doesNotContain("代码仓库：");
    }

    @Test
    @DisplayName("第五段：读父任务抛异常 → 按未声明降级，不阻断建会话")
    void shouldTolerateEnvReadFailure() {
        stubSessionCreation();
        when(taskService.getView(TASK_ID)).thenThrow(new IllegalStateException("db down"));

        assertThat(service.startSession(SUB_TASK_ID, null)).isEqualTo(CONVERSATION_ID);
        assertThat(capturedSeed()).contains("未声明执行环境");
    }

    // ══════════════════════════════════════════════════════════════════
    //  草案期「拆出去」（ADR-008 §5 对话版）：锚 = (父任务, 草案编号)
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("草案期：任务非待确认状态 → 400，不建会话")
    void shouldRejectDraftSessionWhenTaskNotAwaitingConfirm() {
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.IN_PROGRESS, Map.of()));

        assertThatThrownBy(() -> service.startDraftSession(TASK_ID, "202611120001", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有草案待确认状态可拆出去");
        verify(conversationService, never()).save(any());
        verify(messageService, never()).addMessage(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("草案期：草案定位失败 → 400（不留一个终稿也建不出 Y 的半成品会话）")
    void shouldRejectDraftSessionWhenDraftMissing() {
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.AWAITING_PLAN_CONFIRM, Map.of()));
        when(planDraftService.getByCode(TASK_ID, "202611120009")).thenReturn(null);

        assertThatThrownBy(() -> service.startDraftSession(TASK_ID, "202611120009", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("草案定位失败");
        verify(conversationService, never()).save(any());
    }

    @Test
    @DisplayName("草案期：同一草案已有 ACTIVE 会话 → 幂等返回原 id")
    void shouldReuseExistingDraftSession() {
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.AWAITING_PLAN_CONFIRM, Map.of()));
        when(planDraftService.getByCode(TASK_ID, "202611120002"))
                .thenReturn(draft(1L, "202611120002", "写契约", "c", "a", List.of()));
        RequirementConversation existing = new RequirementConversation();
        existing.setId(CONVERSATION_ID);
        when(conversationService.findActiveBySourceDraft(TASK_ID, "202611120002")).thenReturn(existing);

        assertThat(service.startDraftSession(TASK_ID, "202611120002", null)).isEqualTo(CONVERSATION_ID);
        verify(conversationService, never()).save(any());
    }

    @Test
    @DisplayName("草案期：该草案已有活跃派生 → 入口即 400（不让用户聊完才在定稿撞唯一索引）")
    void shouldRejectDraftSessionWhenAlreadyDerived() {
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.AWAITING_PLAN_CONFIRM, Map.of()));
        when(planDraftService.getByCode(TASK_ID, "202611120002"))
                .thenReturn(draft(1L, "202611120002", "写契约", "c", "a", List.of()));
        // 事实判定：派生行还在（deleted=0 且未取消）⇒ 拒；删掉派任务后本方法返回 null ⇒ 放行
        when(taskDerivationService.findActivePlanDerivation(TASK_ID, "202611120002"))
                .thenReturn(taskView(TaskStatus.PENDING, Map.of()));

        assertThatThrownBy(() -> service.startDraftSession(TASK_ID, "202611120002", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该草案已拆出去过")
                .hasMessageContaining("派生任务 #" + TASK_ID);
        verify(conversationService, never()).save(any());
    }

    @Test
    @DisplayName("草案期：锚=父任务+草案编号，种子含草案四要素/上下游/环境/派出前必问段")
    void shouldCreateDraftSessionWithDraftAnchor() {
        when(taskService.getView(TASK_ID)).thenReturn(taskView(TaskStatus.AWAITING_PLAN_CONFIRM,
                Map.of("executionEnv", Map.of(
                        "repo", Map.of("kind", "local", "value", "E:/yhzx/1027/helloai", "baseRef", "main")))));
        PlanDraftView target = draft(1L, "202611120002", "写契约", "产出契约文档", "含四段映射表",
                List.of("202611120001"));
        when(planDraftService.getByCode(TASK_ID, "202611120002")).thenReturn(target);
        when(planDraftService.listByTask(TASK_ID)).thenReturn(List.of(
                draft(0L, "202611120001", "侦察现状", "盘点四条链路现状", "清单齐全", List.of()),
                target,
                draft(2L, "202611120003", "接 CI", "接入 CI job", "能跑通", List.of("202611120002"))));
        when(conversationService.findActiveBySourceDraft(TASK_ID, "202611120002")).thenReturn(null);
        doAnswer(inv -> {
            ((RequirementConversation) inv.getArgument(0)).setId(CONVERSATION_ID);
            return true;
        }).when(conversationService).save(any(RequirementConversation.class));

        Long id = service.startDraftSession(TASK_ID, "202611120002", 88L);

        assertThat(id).isEqualTo(CONVERSATION_ID);
        ArgumentCaptor<RequirementConversation> conv = ArgumentCaptor.forClass(RequirementConversation.class);
        verify(conversationService).save(conv.capture());
        assertThat(conv.getValue().getTaskId()).isEqualTo(TASK_ID);
        // 草案期只有一个锚：sourceDraftCode；SubTask 锚必须为空，否则终稿会走错分支
        assertThat(conv.getValue().getSourceDraftCode()).isEqualTo("202611120002");
        assertThat(conv.getValue().getSourceSubTaskId()).isNull();
        assertThat(conv.getValue().getTitle()).startsWith("草案派生：写契约");

        assertThat(capturedSeed())
                .contains("① 草案原要求", "产出契约文档", "含四段映射表")
                .contains("② 上游草案", "侦察现状")
                .contains("③ 下游草案", "接 CI")
                .contains("④ 执行环境声明", "E:/yhzx/1027/helloai", "main")
                .as("执行前提必须在建任务之前问清：草案直接派生时没人问过仓库/运行时/数据源，"
                        + "Y 拆出来的子任务只能写「仓库中」")
                .contains("⑤ 派出前必须先问清的事项", "仓库", "运行时", "数据源");
    }

    private void stubSessionCreation() {        when(subTaskService.getView(SUB_TASK_ID)).thenReturn(
                view(SUB_TASK_ID, "写周报", "c", "a", Map.of(), List.of(), SubTaskStatus.RESTRUCTURING));
        when(subTaskService.listViewsByTaskId(TASK_ID)).thenReturn(List.of());
        when(conversationService.findActiveBySourceSubTask(SUB_TASK_ID)).thenReturn(null);
        doAnswer(inv -> {
            ((RequirementConversation) inv.getArgument(0)).setId(CONVERSATION_ID);
            return true;
        }).when(conversationService).save(any(RequirementConversation.class));
    }

    private String capturedSeed() {
        ArgumentCaptor<String> seed = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONVERSATION_ID), eq("user"), seed.capture());
        return seed.getValue();
    }
}
