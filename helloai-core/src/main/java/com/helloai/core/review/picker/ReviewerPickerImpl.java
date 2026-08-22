package com.helloai.core.review.picker;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 核验 Reviewer 选型器实现（反馈回路 Phase 4）。
 *
 * <p>选取语义承接原 SubTaskReviewServiceImpl 私有方法（pickReviewerAgent /
 * isUsableReviewer / firstApiKeyLlm，§6.58 P1 指定优先 + 回退链），
 * 双审配对要求两个候选 modelType 不同——不同模型独立判定才有互证价值。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewerPickerImpl implements ReviewerPicker {

    private final AgentService agentService;
    private final AgentSelector agentSelector;
    private final TaskService taskService;

    @Override
    public Agent pickSingle(SubTask subTask) {
        // 任务级指定 reviewerAgentId 优先
        if (subTask != null && subTask.getTaskId() != null) {
            try {
                Task task = taskService.getById(subTask.getTaskId());
                Long policyReviewerId = TaskAgentPolicy.reviewerAgentId(
                        task != null ? task.getAgentPolicy() : null);
                if (policyReviewerId != null) {
                    Agent pinned = agentService.getById(policyReviewerId);
                    if (isUsableReviewer(pinned)) {
                        return pinned;
                    }
                    log.warn("指定的核验 Agent 不可用，回退自动选择: agentId={}, subTaskId={}",
                            policyReviewerId, subTask.getId());
                }
            } catch (Exception e) {
                log.debug("读取任务核验指定失败（按未指定处理）: taskId={}, err={}",
                        subTask.getTaskId(), e.getMessage());
            }
        }
        Agent preferred = agentSelector.pickPreferred(AgentRole.REVIEWER);
        if (preferred != null && preferred.getAccessType() == AgentAccessType.API_KEY_LLM) {
            return preferred;
        }
        Agent reviewer = firstApiKeyLlm(AgentRole.REVIEWER);
        if (reviewer != null) {
            return reviewer;
        }
        return firstApiKeyLlm(AgentRole.PLANNER);
    }

    @Override
    public List<Agent> pickDual(SubTask subTask) {
        List<Agent> candidates = listUsableReviewers();
        if (candidates.size() < 2) {
            // 候选缺失：按实际数量返回（0/1），调用方据此降级单审或等人工
            return candidates;
        }
        // 首位与单审一致：优先 AgentSelector 优选（ACTIVE + API_KEY_LLM），否则取候选首位
        final Agent first;
        Agent preferred = agentSelector.pickPreferred(AgentRole.REVIEWER);
        if (preferred != null && isUsableReviewer(preferred)) {
            first = preferred;
        } else {
            first = candidates.get(0);
        }
        // 次位：与首位 modelType 不同的第一个候选（同模型无互证价值，视为不可配对）
        Agent second = candidates.stream()
                .filter(a -> !a.getId().equals(first.getId()))
                .filter(a -> !Objects.equals(a.getModelType(), first.getModelType()))
                .findFirst()
                .orElse(null);
        if (second == null) {
            log.warn("双审候选全部同模型，无法配对（降级单审）: firstAgentId={}, modelType={}",
                    first.getId(), first.getModelType());
            return List.of(first);
        }
        return List.of(first, second);
    }

    @Override
    public boolean isDualReviewRequired(Long taskId) {
        if (taskId == null) {
            return false;
        }
        try {
            Task task = taskService.getById(taskId);
            if (task == null) {
                return false;
            }
            Map<String, Object> policy = task.getAgentPolicy();
            return TaskAgentPolicy.difficulty(policy) == TaskAgentPolicy.Difficulty.HIGH
                    && TaskAgentPolicy.reviewerAgentId(policy) == null;
        } catch (Exception e) {
            // best-effort：策略解析异常按单审处理，不阻断核验主链路
            log.debug("双审判定降级为 false: taskId={}, err={}", taskId, e.getMessage());
            return false;
        }
    }

    /** 全部可用 REVIEWER 候选（ACTIVE + API_KEY_LLM）。 */
    private List<Agent> listUsableReviewers() {
        List<Agent> candidates = agentService.listByRole(AgentRole.REVIEWER);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Agent> usable = new ArrayList<>();
        for (Agent agent : candidates) {
            if (isUsableReviewer(agent)) {
                usable.add(agent);
            }
        }
        return usable;
    }

    /** 指定的核验 Agent 可用性校验（比创建时宽松失败：不抛错，回退自动）。 */
    private boolean isUsableReviewer(Agent agent) {
        return agent != null
                && agent.getStatus() == AgentStatus.ACTIVE
                && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

    /** 指定角色第一个 API_KEY_LLM Agent（原 firstApiKeyLlm 语义）。 */
    private Agent firstApiKeyLlm(AgentRole role) {
        List<Agent> candidates = agentService.listByRole(role);
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .findFirst()
                .orElse(null);
    }
}
