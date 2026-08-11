package com.helloai.core.planner;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskAgentPolicy;
import com.helloai.core.task.service.TaskService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Planner Agent 选型器（澄清链 / 拆解链共用，收编两处原本刻意复制的 pickPlannerAgent）。
 *
 * <p>选型语义：</p>
 * <ul>
 *   <li>手动指定（pinned）：会话上记录的 planner_agent_id 优先；指定 Agent 失效
 *       （删除/禁用/非平台内）时回退自动选择，不阻断对话。</li>
 *   <li>自动选择：候选=role=PLANNER 且 accessType=API_KEY_LLM 且 ACTIVE 且非 SLEEPING
 *       且有启用态托管凭证；权重一致，优先空闲（in-progress 子任务数最少者）。</li>
 * </ul>
 *
 * <p>外部 Agent（CLI_CLIENT / WEB_BROWSER）走收件箱异步链路，无同步应答桥，
 * 暂不支持担任对话澄清 Planner——{@link #listOptions()} 中展示但标记不可选。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgentPicker {

    private final AgentService agentService;
    private final RequirementConversationService conversationService;
    private final AgentDutyLeaseService agentDutyLeaseService;
    private final CredentialVaultService credentialVaultService;
    private final TaskService taskService;

    /**
     * 按会话钉住的 Planner 选人；pinnedAgentId 为空或失效时走自动选择。
     */
    public Agent pick(Long pinnedAgentId) {
        if (pinnedAgentId != null) {
            Agent pinned = agentService.getById(pinnedAgentId);
            if (isUsable(pinned)) {
                return pinned;
            }
            log.warn("指定的 Planner Agent 不可用，回退自动选择: agentId={}", pinnedAgentId);
        }
        return autoPick();
    }

    /**
     * 拆解链入口：按 taskId 选 Planner（§6.58 P1 指定语义）。
     *
     * <p>优先级：
     * <ol>
     *   <li>任务级 {@code task.agent_policy.plannerAgentId}（V47）——任务创建时
     *       显式指定的 Planner，优先于会话记录；失效（删除/禁用）时由
     *       {@link #pick(Long)} 回退自动选择；</li>
     *   <li>澄清会话钉住的 Planner（澄清→拆解同一 Planner 跟随）；</li>
     *   <li>无会话或未钉住时走自动选择。</li>
     * </ol>
     * </p>
     */
    public Agent pickForTask(Long taskId) {
        // V47：任务级 agent_policy.plannerAgentId 优先
        if (taskId != null) {
            Task task = taskService.getById(taskId);
            if (task != null) {
                Long policyPlannerId = TaskAgentPolicy.plannerAgentId(task.getAgentPolicy());
                if (policyPlannerId != null) {
                    return pick(policyPlannerId);
                }
            }
        }
        Long pinnedAgentId = null;
        if (taskId != null) {
            RequirementConversation conversation = conversationService.lambdaQuery()
                    .eq(RequirementConversation::getTaskId, taskId)
                    .isNotNull(RequirementConversation::getPlannerAgentId)
                    .orderByDesc(RequirementConversation::getCreateTime)
                    .last("LIMIT 1")
                    .one();
            if (conversation != null) {
                pinnedAgentId = conversation.getPlannerAgentId();
            }
        }
        return pick(pinnedAgentId);
    }

    /**
     * 新建会话时校验用户手动指定的 Planner 是否可选（防御前端绕过置灰）。
     */
    public void validateSelectable(Long agentId) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("指定的 Planner Agent 不存在: " + agentId);
        }
        if (agent.getRole() != AgentRole.PLANNER) {
            throw new BizException("指定的 Agent 不是 PLANNER 角色: " + agent.getName());
        }
        if (agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            throw new BizException("外部 Agent 暂不支持对话澄清，请选择平台内 Planner: " + agent.getName());
        }
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new BizException("指定的 Planner Agent 已被禁用: " + agent.getName());
        }
    }

    /**
     * 前端下拉选数据源：平台内 PLANNER（可选）+ 在班外部 Agent（展示但置灰）。
     */
    public List<PlannerOption> listOptions() {
        List<PlannerOption> options = new ArrayList<>();
        for (Agent agent : agentService.listByRole(AgentRole.PLANNER)) {
            if (agent.getStatus() != AgentStatus.ACTIVE
                    || agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
                continue;
            }
            boolean hasCredential = hasUsableCredential(agent);
            options.add(buildOption(agent, isOnDuty(agent.getId()), hasCredential,
                    hasCredential ? null : "未配置可用凭证"));
        }
        // 在班（值班租约 ACTIVE）的外部 Agent：任意角色都展示，置灰并注明原因
        for (Agent agent : agentService.listActive()) {
            if (agent.getAccessType() == AgentAccessType.API_KEY_LLM) {
                continue;
            }
            if (!isOnDuty(agent.getId())) {
                continue;
            }
            options.add(buildOption(agent, true, false, "外部 Agent 暂不支持对话澄清"));
        }
        return options;
    }

    /**
     * 自动选择：候选权重一致，优先空闲（in-progress 子任务数最少）。
     */
    private Agent autoPick() {
        return agentService.listByRole(AgentRole.PLANNER).stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.SLEEPING)
                .filter(this::hasUsableCredential)
                .min(Comparator.comparingInt(a -> agentService.inProgressCount(a.getId())))
                .orElseThrow(() -> new BizException(
                        "无可用的平台内 Planner Agent（需要 role=PLANNER 且 accessType=API_KEY_LLM）；"
                                + "请先在 Agent 管理中注册，或改用外部 Planner Agent 手工创建子任务"));
    }

    /** pinned Agent 使用时校验（比 create 时校验宽松失败：不抛错，回退自动）。 */
    private boolean isUsable(Agent agent) {
        return agent != null
                && agent.getStatus() == AgentStatus.ACTIVE
                && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

    /** 凭证可用性检查（照 AgentSelector.hasUsableCredential 的防御式降级）。 */
    private boolean hasUsableCredential(Agent agent) {
        if (agent == null || agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            return true;
        }
        try {
            return credentialVaultService.hasActiveAgentCredential(agent.getId());
        } catch (Exception e) {
            log.debug("hasUsableCredential fallback to false for agent {}: {}",
                    agent.getId(), e.getMessage());
            return false;
        }
    }

    /** 值班判定（防御式：查询异常降级为非值班，不影响选项列表）。 */
    private boolean isOnDuty(Long agentId) {
        try {
            return agentDutyLeaseService.isOnDuty(agentId);
        } catch (Exception e) {
            log.debug("isOnDuty fallback to false for agent {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    private PlannerOption buildOption(Agent agent, boolean onDuty,
                                      boolean selectable, String disabledReason) {
        PlannerOption option = new PlannerOption();
        option.setId(agent.getId());
        option.setName(agent.getName());
        option.setRole(agent.getRole());
        option.setAccessType(agent.getAccessType());
        option.setModelType(agent.getModelType());
        option.setOnDuty(onDuty);
        option.setSelectable(selectable);
        option.setDisabledReason(disabledReason);
        return option;
    }

    /** Planner 下拉选项（selectable=false 时 disabledReason 说明置灰原因）。 */
    @Data
    public static class PlannerOption {
        private Long id;
        private String name;
        private AgentRole role;
        private AgentAccessType accessType;
        private String modelType;
        private boolean onDuty;
        private boolean selectable;
        private String disabledReason;
    }
}
