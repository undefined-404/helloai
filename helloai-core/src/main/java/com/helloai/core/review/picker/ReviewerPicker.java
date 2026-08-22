package com.helloai.core.review.picker;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;

import java.util.List;

/**
 * 核验 Reviewer 选型器（反馈回路 Phase 4 双审前置拆分）。
 *
 * <p>「选取策略」与「审核编排」分离：单审/双审的 Reviewer 选取统一收口
 * 本接口（对齐 {@code planner/picker/PlannerAgentPicker} 先例），
 * SubTaskReviewServiceImpl 只保留编排，双审与抽检复用同一选取逻辑。</p>
 *
 * <p>可配置前提（{@code helloai.review.*}）：双审仅作用于未指定
 * reviewerAgentId 的自动兜底路径，与任务级指定语义互斥。</p>
 */
public interface ReviewerPicker {

    /**
     * 单审选取（原 SubTaskReviewServiceImpl.pickReviewerAgent 语义）：
     * 任务级指定 reviewerAgentId 优先 → 指定失效或未指定时回退链
     * AgentSelector 优选 REVIEWER（API_KEY_LLM）→ 同角色 API_KEY_LLM
     * → PLANNER 角色 API_KEY_LLM。
     *
     * @param subTask 待核验子任务（可空；taskId 为空时直接走回退链）
     * @return 可用核验 Agent；无可用时返回 null
     */
    Agent pickSingle(SubTask subTask);

    /**
     * 双审选取：返回两个 {@code modelType}（provider:model 整体比对）不同的
     * API_KEY_LLM REVIEWER（ACTIVE）。候选不足按实际数量返回，size∈[0,2]；
     * 调用方按 size==2 判定双审、否则降级单审。
     *
     * @param subTask 待核验子任务（可空）
     * @return 0~2 个 Reviewer；size&lt;2 表示无法配对（候选缺失或全同模型）
     */
    List<Agent> pickDual(SubTask subTask);

    /**
     * 判定是否应走双审：任务 {@code task.agent_policy.difficulty==HIGH}
     * 且未指定 reviewerAgentId（指定则与双审互斥，走单审）。
     *
     * @param taskId 主任务 ID；null/任务不存在/策略解析异常时返回 false（best-effort）
     * @return true=应走双审
     */
    boolean isDualReviewRequired(Long taskId);
}
