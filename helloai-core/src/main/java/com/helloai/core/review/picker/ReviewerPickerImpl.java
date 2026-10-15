package com.helloai.core.review.picker;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 核验 Reviewer 选型器实现（反馈回路）。
 *
 * <p>选取语义承接原 SubTaskReviewServiceImpl 私有方法（pickReviewerAgent /
 * isUsableReviewer / firstApiKeyLlm，§6.58 P1 指定优先 + 回退链），
 * 双审配对要求两个候选 modelType 不同——不同模型独立判定才有互证价值。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewerPickerImpl implements ReviewerPicker {

    /** 无核验履历时的中性档（`MED-1`）：不偏袒、不惩罚新人 Reviewer。 */
    private static final int REVIEWER_QUALITY_NEUTRAL = 50;

    private final AgentService agentService;
    private final AgentSelector agentSelector;
    private final TaskService taskService;
    /** 核验侧质量分来源（`MED-1`）；只用 reviewer 维度字段，与执行侧质量分互不污染。 */
    private final AgentQualityProfileService agentQualityProfileService;

    @Override
    public AgentProfileSnapshot pickSingle(SubTaskView subTask) {
        // 任务级指定 reviewerAgentId 优先
        if (subTask != null && subTask.taskId() != null) {
            try {
                TaskView task = taskService.getView(subTask.taskId());
                Long policyReviewerId = TaskAgentPolicy.reviewerAgentId(
                        task != null ? task.agentPolicy() : null);
                if (policyReviewerId != null) {
                    AgentProfileSnapshot pinned = agentService.getProfileById(policyReviewerId);
                    if (isUsableReviewer(pinned)) {
                        return pinned;
                    }
                    log.warn("指定的核验 Agent 不可用，回退自动选择: agentId={}, subTaskId={}",
                            policyReviewerId, subTask.id());
                }
            } catch (Exception e) {
                log.debug("读取任务核验指定失败（按未指定处理）: taskId={}, err={}",
                        subTask.taskId(), e.getMessage());
            }
        }
        AgentProfileSnapshot preferred = agentSelector.pickPreferredProfile(AgentRole.REVIEWER);
        if (preferred != null && preferred.accessType() == AgentAccessType.API_KEY_LLM) {
            return preferred;
        }
        AgentProfileSnapshot reviewer = firstApiKeyLlm(AgentRole.REVIEWER);
        if (reviewer != null) {
            return reviewer;
        }
        return firstApiKeyLlm(AgentRole.PLANNER);
    }

    @Override
    public List<AgentProfileSnapshot> pickDual(SubTaskView subTask) {
        List<AgentProfileSnapshot> candidates = listUsableReviewers();
        if (candidates.size() < 2) {
            // 候选缺失：按实际数量返回（0/1），调用方据此降级单审或等人工
            return candidates;
        }
        // 首位与单审一致：优先 AgentSelector 优选（ACTIVE + API_KEY_LLM），否则取候选首位
        final AgentProfileSnapshot first;
        AgentProfileSnapshot preferred = agentSelector.pickPreferredProfile(AgentRole.REVIEWER);
        if (preferred != null && isUsableReviewer(preferred)) {
            first = preferred;
        } else {
            first = candidates.get(0);
        }
        // 次位：与首位 modelType 不同的第一个候选（同模型无互证价值，视为不可配对）
        AgentProfileSnapshot second = candidates.stream()
                .filter(a -> !a.id().equals(first.id()))
                .filter(a -> !Objects.equals(a.modelType(), first.modelType()))
                .findFirst()
                .orElse(null);
        if (second == null) {
            log.warn("双审候选全部同模型，无法配对（降级单审）: firstAgentId={}, modelType={}",
                    first.id(), first.modelType());
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
            TaskView task = taskService.getView(taskId);
            if (task == null) {
                return false;
            }
            Map<String, Object> policy = task.agentPolicy();
            return TaskAgentPolicy.difficulty(policy) == TaskAgentPolicy.Difficulty.HIGH
                    && TaskAgentPolicy.reviewerAgentId(policy) == null;
        } catch (Exception e) {
            // best-effort：策略解析异常按单审处理，不阻断核验主链路
            log.debug("双审判定降级为 false: taskId={}, err={}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 全部可用 REVIEWER 候选（ACTIVE + API_KEY_LLM）。
     *
     * <p><b>`MED-1`（2026-10-15）：按核验侧质量分降序</b>。
     * 修复前此列表按 {@code agentService.listProfilesByRole} 的原始顺序返回，
     * 而 {@code AgentSelector} 的 {@code qualityRank} 用的是<b>执行侧</b>指标
     * ⇒ 选核验人时完全忽略其核验履历（{@code reviewerDisagreementCount} 全仓零读取），
     * 分歧率高（核验过松/过严）的 Reviewer 不会被降档，无纠正回路。</p>
     *
     * <p>排序为<b>软</b>维度：只改优先级、不剔除任何候选（分歧率高也可能是严格的正确判断）；
     * 无核验履历（分数 {@code null}）按<b>中性档 50</b> 处理——「不可比」的落点是
     * <b>不偏袒</b>，而非记 0 惩罚新人（同 {@code computeQualityScore} 的既有约定）。</p>
     */
    private List<AgentProfileSnapshot> listUsableReviewers() {
        List<AgentProfileSnapshot> candidates = agentService.listProfilesByRole(AgentRole.REVIEWER);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<AgentProfileSnapshot> usable = new ArrayList<>();
        for (AgentProfileSnapshot agent : candidates) {
            if (isUsableReviewer(agent)) {
                usable.add(agent);
            }
        }
        // 稳定排序：质量分相同者保持原相对顺序（listProfilesByRole 的次序仍有意义）
        usable.sort(Comparator.comparingInt(this::reviewerQualityRank).reversed());
        return usable;
    }

    /** 核验侧质量档位：0~100 分；无信号（无画像 / 核验次数 0）取中性 50。查询异常降级为中性，不阻断选人。 */
    private int reviewerQualityRank(AgentProfileSnapshot agent) {
        try {
            Integer score = agentQualityProfileService.computeReviewerQualityScore(agent.id());
            return score != null ? score : REVIEWER_QUALITY_NEUTRAL;
        } catch (Exception e) {
            log.debug("核验侧质量分查询异常，按中性档处理: agentId={}, err={}", agent.id(), e.getMessage());
            return REVIEWER_QUALITY_NEUTRAL;
        }
    }

    /** 指定的核验 Agent 可用性校验（比创建时宽松失败：不抛错，回退自动）。 */
    private boolean isUsableReviewer(AgentProfileSnapshot agent) {
        return agent != null
                && agent.status() == AgentStatus.ACTIVE
                && agent.accessType() == AgentAccessType.API_KEY_LLM;
    }

    /** 指定角色第一个 API_KEY_LLM Agent（原 firstApiKeyLlm 语义）。 */
    private AgentProfileSnapshot firstApiKeyLlm(AgentRole role) {
        List<AgentProfileSnapshot> candidates = agentService.listProfilesByRole(role);
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(a -> a.accessType() == AgentAccessType.API_KEY_LLM)
                .findFirst()
                .orElse(null);
    }
}
