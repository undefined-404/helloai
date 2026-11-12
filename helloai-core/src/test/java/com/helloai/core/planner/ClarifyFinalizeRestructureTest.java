package com.helloai.core.planner;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.clarify.SkillDirective;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.mapper.RequirementConversationMapper;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.service.impl.ClarifyFinalizeServiceImpl;
import com.helloai.core.task.port.TaskDraft;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskDerivationService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("重构会话定稿建 Y（ADR-008 §13）")
class ClarifyFinalizeRestructureTest {

    private static final long CONVERSATION_ID = 300L;
    private static final long PARENT_TASK_ID = 9L;
    private static final long SOURCE_SUB_TASK_ID = 5L;
    private static final long DERIVED_TASK_ID = 500L;
    private static final String DRAFT_CODE = "202611120001";

    @Mock private RequirementConversationService conversationService;
    @Mock private RequirementMessageService messageService;
    @Mock private TaskService taskService;
    @Mock private AgentService agentService;
    @Mock private AgentInboxService agentInboxService;
    @Mock private TaskTimelineService taskTimelineService;
    @Mock private LongTermMemoryService longTermMemoryService;
    @Mock private RequirementConversationMapper requirementConversationMapper;
    @Mock private ClarifyCommandSearchService commandSearch;
    @Mock private SkillDirective skillDirective;
    @Mock private TaskDerivationService taskDerivationService;
    @Mock private PlanDraftService planDraftService;
    @Mock private LambdaUpdateChainWrapper<RequirementConversation> casWrapper;

    private ClarifyFinalizeServiceImpl finalizeService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        finalizeService = new ClarifyFinalizeServiceImpl(
                conversationService, messageService, taskService, agentService, agentInboxService,
                taskTimelineService, longTermMemoryService, requirementConversationMapper,
                new ObjectMapper(), commandSearch, skillDirective, taskDerivationService, planDraftService);
        when(conversationService.lambdaUpdate()).thenReturn(casWrapper);
        when(casWrapper.eq(any(SFunction.class), any())).thenReturn(casWrapper);
        when(casWrapper.set(any(SFunction.class), any())).thenReturn(casWrapper);
        when(casWrapper.update()).thenReturn(true);
        when(messageService.listByConversation(CONVERSATION_ID)).thenReturn(List.of());
        when(agentService.listProfilesByRole(AgentRole.PLANNER)).thenReturn(List.of());
    }

    @Test
    @DisplayName("带 sourceSubTaskId 的会话定稿 → 走 createRestructureTask，不回填 task_id")
    void shouldCreateDerivedTaskAndKeepParentTaskId() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setStatus("ACTIVE");
        conversation.setFinalTitle("重构后的选点分析");
        conversation.setFinalDescription("重做选点");
        conversation.setTaskId(PARENT_TASK_ID);
        conversation.setSourceSubTaskId(SOURCE_SUB_TASK_ID);
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(conversation);
        when(taskDerivationService.restructureViable(SOURCE_SUB_TASK_ID)).thenReturn(true);
        when(taskDerivationService.createRestructureTask(any(TaskDraft.class), eq(SOURCE_SUB_TASK_ID)))
                .thenReturn(new TaskView(DERIVED_TASK_ID, "重构后的选点分析", "重做选点",
                        null, null, null, null, Map.of(), TaskStatus.PENDING, null, null,
                        Map.of(), List.of(), null, null, null, 1, PARENT_TASK_ID));

        TaskView view = finalizeService.finalizeAfterDraft(conversation, "task_created_from_clarify");

        assertThat(view.id()).isEqualTo(DERIVED_TASK_ID);
        verify(taskDerivationService).createRestructureTask(any(TaskDraft.class), eq(SOURCE_SUB_TASK_ID));
        verify(taskService, never()).createFromDraft(any());
        // task_id 保持父任务 X（ADR-008 §13.1），产出任务写 derived_task_id（V150）
        assertThat(conversation.getTaskId()).isEqualTo(PARENT_TASK_ID);
        assertThat(conversation.getDerivedTaskId()).isEqualTo(DERIVED_TASK_ID);
    }

    @Test
    @DisplayName("带 sourceDraftCode 的会话定稿 → 走 createDraftDeriveTask（父锚=任务+草案号），不回填 task_id")
    void shouldCreateDraftDerivedTaskAndKeepParentTaskId() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setStatus("ACTIVE");
        conversation.setFinalTitle("派生后的冒烟编排");
        conversation.setFinalDescription("把编排脚本独立成任务");
        conversation.setTaskId(PARENT_TASK_ID);
        conversation.setSourceDraftCode(DRAFT_CODE);
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(conversation);
        when(planDraftService.getByCode(PARENT_TASK_ID, DRAFT_CODE)).thenReturn(mock(PlanDraftView.class));
        when(taskDerivationService.createDraftDeriveTask(any(TaskDraft.class), eq(PARENT_TASK_ID), eq(DRAFT_CODE)))
                .thenReturn(new TaskView(DERIVED_TASK_ID, "派生后的冒烟编排", "把编排脚本独立成任务",
                        null, null, null, null, Map.of(), TaskStatus.PENDING, null, null,
                        Map.of(), List.of(), null, null, null, 1, PARENT_TASK_ID));

        TaskView view = finalizeService.finalizeAfterDraft(conversation, "task_created_from_clarify");

        assertThat(view.id()).isEqualTo(DERIVED_TASK_ID);
        verify(taskDerivationService).createDraftDeriveTask(any(TaskDraft.class), eq(PARENT_TASK_ID), eq(DRAFT_CODE));
        verify(taskDerivationService, never()).createRestructureTask(any(), any());
        verify(taskService, never()).createFromDraft(any());
        // 草案期派生同样保持父任务（父锚是 parent_draft_code），产出任务写 derived_task_id
        assertThat(conversation.getTaskId()).isEqualTo(PARENT_TASK_ID);
        assertThat(conversation.getDerivedTaskId()).isEqualTo(DERIVED_TASK_ID);
    }

    @Test
    @DisplayName("派生任务已删 → regenerate 可重新生成 Y（判据用产出指针，不被父任务 X 挡住）")
    void shouldAllowRegenerateWhenDerivedTaskGone() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setStatus("FINALIZED");
        conversation.setFinalTitle("派生后的冒烟编排");
        conversation.setFinalDescription("把编排脚本独立成任务");
        conversation.setTaskId(PARENT_TASK_ID);
        conversation.setSourceDraftCode(DRAFT_CODE);
        conversation.setDerivedTaskId(DERIVED_TASK_ID);
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(conversation);
        // 父任务 X 还在，但产出任务 Y 已被删除
        when(taskService.getView(DERIVED_TASK_ID)).thenReturn(null);
        when(planDraftService.getByCode(PARENT_TASK_ID, DRAFT_CODE)).thenReturn(mock(PlanDraftView.class));
        when(taskDerivationService.createDraftDeriveTask(any(TaskDraft.class), eq(PARENT_TASK_ID), eq(DRAFT_CODE)))
                .thenReturn(new TaskView(DERIVED_TASK_ID, "派生后的冒烟编排", "把编排脚本独立成任务",
                        null, null, null, null, Map.of(), TaskStatus.PENDING, null, null,
                        Map.of(), List.of(), null, null, null, 1, PARENT_TASK_ID));

        TaskView view = finalizeService.regenerate(CONVERSATION_ID);

        assertThat(view.id()).isEqualTo(DERIVED_TASK_ID);
        verify(taskDerivationService).createDraftDeriveTask(any(TaskDraft.class), eq(PARENT_TASK_ID), eq(DRAFT_CODE));
    }

    @Test
    @DisplayName("来源草案已不存在（已确认/已作废）→ 400，不建没有有效父锚的游离任务")
    void shouldRejectDraftDeriveWhenDraftGone() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setStatus("ACTIVE");
        conversation.setFinalTitle("派生后的冒烟编排");
        conversation.setTaskId(PARENT_TASK_ID);
        conversation.setSourceDraftCode(DRAFT_CODE);
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(conversation);
        when(planDraftService.getByCode(PARENT_TASK_ID, DRAFT_CODE)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> finalizeService.finalizeAfterDraft(conversation, "task_created_from_clarify"))
                .isInstanceOf(com.helloai.common.base.BizException.class)
                .hasMessageContaining("来源草案已不在暂存区");
        verify(taskDerivationService, never()).createDraftDeriveTask(any(), any(), any());
        verify(taskService, never()).createFromDraft(any());
    }

    @Test
    @DisplayName("解耦方向一：父 SubTask 已删 → 回退普通任务，回填 task_id")
    void shouldFallbackToPlainTaskWhenParentDeleted() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setStatus("ACTIVE");
        conversation.setFinalTitle("重构后的选点分析");
        conversation.setFinalDescription("重做选点");
        conversation.setTaskId(PARENT_TASK_ID);
        conversation.setSourceSubTaskId(SOURCE_SUB_TASK_ID);
        // 上级任务/子任务已被级联删除 → restructureViable 返回 false（仅存在性判断）
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(conversation);
        when(taskDerivationService.restructureViable(SOURCE_SUB_TASK_ID)).thenReturn(false);
        when(taskService.createFromDraft(any(TaskDraft.class)))
                .thenReturn(new TaskView(DERIVED_TASK_ID, "重构后的选点分析", "重做选点",
                        null, null, null, null, Map.of(), TaskStatus.PENDING, null, null,
                        Map.of(), List.of(), null, null, null, null, null));

        TaskView view = finalizeService.finalizeAfterDraft(conversation, "task_created_from_clarify");

        assertThat(view.id()).isEqualTo(DERIVED_TASK_ID);
        verify(taskDerivationService, never()).createRestructureTask(any(), any());
        verify(taskService).createFromDraft(any());
        // 回退后新任务成为独立任务，task_id 回填指向它（不挂已删除的上级）
        assertThat(conversation.getTaskId()).isEqualTo(DERIVED_TASK_ID);
    }

    @Test
    @DisplayName("createRestructureTask 拒绝时不降级（状态判据归 task 域，planner 只保证不吞异常）")
    void shouldNotFallbackWhenParentExistsButNotRestructuring() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setStatus("ACTIVE");
        conversation.setFinalTitle("重构后的选点分析");
        conversation.setFinalDescription("重做选点");
        conversation.setTaskId(PARENT_TASK_ID);
        conversation.setSourceSubTaskId(SOURCE_SUB_TASK_ID);
        // 父行还在 → restructureViable=true → 仍走派生，拒绝原因（状态不对 / 已有活跃派生）
        // 由 createRestructureTask 内部的 assertDerivable 抛出，本类不得静默降级。
        // 具体拒绝口径见 TaskDerivationServiceImplTest 的 assertDerivable 用例。
        when(conversationService.getById(CONVERSATION_ID)).thenReturn(conversation);
        when(taskDerivationService.restructureViable(SOURCE_SUB_TASK_ID)).thenReturn(true);
        when(taskDerivationService.createRestructureTask(any(TaskDraft.class), eq(SOURCE_SUB_TASK_ID)))
                .thenThrow(new com.helloai.common.base.BizException(400,
                        "该子任务已完成二次拆分（派生任务 #71），如需重新拆分请先删除或作废该派生任务"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> finalizeService.finalizeAfterDraft(conversation, "task_created_from_clarify"))
                .isInstanceOf(com.helloai.common.base.BizException.class)
                .hasMessageContaining("已完成二次拆分");
        verify(taskService, never()).createFromDraft(any());
        // 未降级 → task_id 保持父任务 X，不被回填
        assertThat(conversation.getTaskId()).isEqualTo(PARENT_TASK_ID);
    }
}
