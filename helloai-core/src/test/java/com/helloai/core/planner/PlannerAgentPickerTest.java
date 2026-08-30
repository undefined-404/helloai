package com.helloai.core.planner;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerAgentPicker 单元测试：
 * pinned 有效直用 / 失效回退自动 / 自动选择优先空闲（等权重）/
 * 无候选报错 / validateSelectable 拒外部与禁用 / pickForTask 反查会话 /
 * listOptions 组成（平台内可选 + 在班外部置灰）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerAgentPicker")
class PlannerAgentPickerTest {

    private static final Long TASK_ID = 100L;

    @Mock
    private AgentService agentService;

    @Mock
    private RequirementConversationService conversationService;

    @Mock
    private AgentDutyLeaseService agentDutyLeaseService;

    @Mock
    private AgentLlmCredentialResolver agentLlmCredentialResolver;

    @Mock
    private TaskService taskService;

    private PlannerAgentPicker picker;

    @BeforeEach
    void setUp() {
        picker = new PlannerAgentPicker(agentService, conversationService,
                agentDutyLeaseService, agentLlmCredentialResolver, taskService);
        // 默认：全部凭证可用（单测按需覆盖）
        lenient().when(agentLlmCredentialResolver.hasUsableCredential(any())).thenReturn(true);
    }

    private Agent llmPlanner(long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName("planner-" + id);
        agent.setRole(AgentRole.PLANNER);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setOnlineStatus(AgentOnlineStatus.IDLE);
        return agent;
    }

    private Agent cliAgent(long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName("cli-" + id);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        return agent;
    }

    // ══════════════════════════════════════════════════════════════
    //  pick：pinned 优先 / 失效回退自动
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("pinned 有效时直接使用，不走自动选择")
    void shouldUsePinnedAgentWhenUsable() {
        Agent pinned = llmPlanner(9L);
        when(agentService.getById(9L)).thenReturn(pinned);

        Agent picked = picker.pick(9L);

        assertThat(picked).isSameAs(pinned);
        verify(agentService, never()).listByRole(any());
    }

    @Test
    @DisplayName("pinned 已禁用时回退自动选择")
    void shouldFallbackToAutoWhenPinnedDisabled() {
        Agent pinned = llmPlanner(9L);
        pinned.setStatus(AgentStatus.DISABLED);
        when(agentService.getById(9L)).thenReturn(pinned);
        Agent auto = llmPlanner(10L);
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of(auto));

        assertThat(picker.pick(9L).getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("pinned 已被删除（getById 为 null）时回退自动选择")
    void shouldFallbackToAutoWhenPinnedMissing() {
        when(agentService.getById(9L)).thenReturn(null);
        Agent auto = llmPlanner(10L);
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of(auto));

        assertThat(picker.pick(9L).getId()).isEqualTo(10L);
    }

    // ══════════════════════════════════════════════════════════════
    //  自动选择：等权重，优先空闲
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("自动选择：等权重，in-progress 子任务最少者优先")
    void shouldPickIdlestCandidate() {
        Agent busy = llmPlanner(1L);
        Agent idle = llmPlanner(2L);
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of(busy, idle));
        when(agentService.inProgressCount(1L)).thenReturn(3);
        when(agentService.inProgressCount(2L)).thenReturn(0);

        assertThat(picker.pick(null).getId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("自动选择：过滤 SLEEPING、非 API_KEY_LLM、无凭证候选")
    void shouldFilterOutIneligibleCandidates() {
        Agent sleeping = llmPlanner(1L);
        sleeping.setOnlineStatus(AgentOnlineStatus.SLEEPING);
        Agent external = cliAgent(2L);
        external.setRole(AgentRole.PLANNER);
        Agent noCredential = llmPlanner(3L);
        Agent eligible = llmPlanner(4L);
        when(agentService.listByRole(AgentRole.PLANNER))
                .thenReturn(List.of(sleeping, external, noCredential, eligible));
        when(agentLlmCredentialResolver.hasUsableCredential(noCredential)).thenReturn(false);
        when(agentLlmCredentialResolver.hasUsableCredential(eligible)).thenReturn(true);

        assertThat(picker.pick(null).getId()).isEqualTo(4L);
    }

    @Test
    @DisplayName("自动选择：无可用候选时抛 BizException 并附操作指引")
    void shouldThrowWhenNoCandidate() {
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of());

        assertThatThrownBy(() -> picker.pick(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无可用的平台内 Planner Agent");
    }

    // ══════════════════════════════════════════════════════════════
    //  pickForTask：按 taskId 反查澄清会话钉住的 Planner
    // ══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private void stubConversationQuery(RequirementConversation result) {
        LambdaQueryChainWrapper<RequirementConversation> chain = mock(LambdaQueryChainWrapper.class);
        when(conversationService.lambdaQuery()).thenReturn(chain);
        doReturn(chain).when(chain).eq(any(), any());
        doReturn(chain).when(chain).isNotNull(any());
        doReturn(chain).when(chain)
                .orderByDesc(ArgumentMatchers.<SFunction<RequirementConversation, ?>>any());
        doReturn(chain).when(chain).last(anyString());
        when(chain.one()).thenReturn(result);
    }

    @Test
    @DisplayName("pickForTask：会话钉住 Planner 时按钉住的 ID 选人（拆解跟随澄清）")
    void shouldFollowPinnedPlannerFromConversation() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setPlannerAgentId(9L);
        stubConversationQuery(conversation);
        Agent pinned = llmPlanner(9L);
        when(agentService.getById(9L)).thenReturn(pinned);

        assertThat(picker.pickForTask(TASK_ID)).isSameAs(pinned);
    }

    @Test
    @DisplayName("pickForTask：无钉住会话时走自动选择")
    void shouldAutoPickWhenNoPinnedConversation() {
        stubConversationQuery(null);
        Agent auto = llmPlanner(10L);
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of(auto));

        assertThat(picker.pickForTask(TASK_ID).getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("pickForTask：任务级 agent_policy.plannerAgentId 优先于会话钉住")
    void shouldPreferPolicyPlannerOverConversationPinned() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setAgentPolicy(TaskAgentPolicy.build(9L, null, null, null, null));
        when(taskService.getById(TASK_ID)).thenReturn(task);
        Agent policyPlanner = llmPlanner(9L);
        when(agentService.getById(9L)).thenReturn(policyPlanner);

        assertThat(picker.pickForTask(TASK_ID)).isSameAs(policyPlanner);
        // 会话即使钉住其他 Planner 也不应被查询——policy 指定优先
        verify(conversationService, never()).lambdaQuery();
    }

    @Test
    @DisplayName("pickForTask：policy 指定 Planner 失效（禁用）时回退自动选择")
    void shouldFallbackToAutoWhenPolicyPlannerDisabled() {
        Task task = new Task();
        task.setId(TASK_ID);
        task.setAgentPolicy(TaskAgentPolicy.build(9L, null, null, null, null));
        when(taskService.getById(TASK_ID)).thenReturn(task);
        Agent disabled = llmPlanner(9L);
        disabled.setStatus(AgentStatus.DISABLED);
        when(agentService.getById(9L)).thenReturn(disabled);
        Agent auto = llmPlanner(10L);
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of(auto));

        assertThat(picker.pickForTask(TASK_ID)).isSameAs(auto);
        verify(conversationService, never()).lambdaQuery();
    }

    // ══════════════════════════════════════════════════════════════
    //  validateSelectable
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("validateSelectable：外部 Agent 拒绝（暂不支持对话澄清）")
    void shouldRejectExternalAgent() {
        Agent external = cliAgent(8L);
        external.setRole(AgentRole.PLANNER);
        when(agentService.getById(8L)).thenReturn(external);

        assertThatThrownBy(() -> picker.validateSelectable(8L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持对话澄清");
    }

    @Test
    @DisplayName("validateSelectable：非 PLANNER 角色 / 禁用 / 不存在均拒绝")
    void shouldRejectNonPlannerOrDisabledOrMissing() {
        Agent executor = llmPlanner(7L);
        executor.setRole(AgentRole.EXECUTOR);
        when(agentService.getById(7L)).thenReturn(executor);
        assertThatThrownBy(() -> picker.validateSelectable(7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是 PLANNER 角色");

        Agent disabled = llmPlanner(6L);
        disabled.setStatus(AgentStatus.DISABLED);
        when(agentService.getById(6L)).thenReturn(disabled);
        assertThatThrownBy(() -> picker.validateSelectable(6L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被禁用");

        when(agentService.getById(999L)).thenReturn(null);
        assertThatThrownBy(() -> picker.validateSelectable(999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
    }

    // ══════════════════════════════════════════════════════════════
    //  listOptions
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("listOptions：平台内 PLANNER 可选，在班外部 Agent 展示但置灰")
    void shouldListInternalSelectableAndExternalGreyed() {
        Agent internal = llmPlanner(1L);
        Agent internalNoCred = llmPlanner(2L);
        when(agentService.listByRole(AgentRole.PLANNER))
                .thenReturn(List.of(internal, internalNoCred));
        when(agentLlmCredentialResolver.hasUsableCredential(internal)).thenReturn(true);
        when(agentLlmCredentialResolver.hasUsableCredential(internalNoCred)).thenReturn(false);

        Agent onDutyExternal = cliAgent(3L);
        Agent offDutyExternal = cliAgent(4L);
        when(agentService.listActive()).thenReturn(List.of(onDutyExternal, offDutyExternal));
        when(agentDutyLeaseService.isOnDuty(3L)).thenReturn(true);
        when(agentDutyLeaseService.isOnDuty(4L)).thenReturn(false);
        lenient().when(agentDutyLeaseService.isOnDuty(1L)).thenReturn(false);
        lenient().when(agentDutyLeaseService.isOnDuty(2L)).thenReturn(false);

        List<PlannerAgentPicker.PlannerOption> options = picker.listOptions();

        assertThat(options).hasSize(3);
        assertThat(options).filteredOn(o -> o.getId() == 1L)
                .singleElement()
                .satisfies(o -> assertThat(o.isSelectable()).isTrue());
        assertThat(options).filteredOn(o -> o.getId() == 2L)
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.isSelectable()).isFalse();
                    assertThat(o.getDisabledReason()).contains("凭证");
                });
        assertThat(options).filteredOn(o -> o.getId() == 3L)
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.isSelectable()).isFalse();
                    assertThat(o.isOnDuty()).isTrue();
                    assertThat(o.getDisabledReason()).contains("暂不支持对话澄清");
                });
        // 不在班的外部 Agent 不出现在下拉选
        assertThat(options).noneMatch(o -> o.getId() == 4L);
    }
}
