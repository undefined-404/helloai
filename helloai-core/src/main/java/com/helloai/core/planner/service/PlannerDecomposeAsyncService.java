package com.helloai.core.planner.service;

import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;

import java.util.List;

/**
 * Planner 任务拆解异步执行服务。
 *
 * <p>拆解异步化改造：{@code decompose} 提交即返回后，LLM 拆解段由本服务在专用线程池
 * {@code plannerDecomposeExecutor} 上异步执行（实现类 {@code PlannerDecomposeAsyncServiceImpl}
 * 以 {@code @Async} 标注）。入口与出口均收敛在本接口，供同步守卫跨类调用以激活异步代理。</p>
 *
 * <p>失败路径由实现内部闭环（回退 PLANNING → PENDING + 记录 {@code task_plan_failed}），
 * 另由 helloai-job 的 {@code PlanningTimeoutTask} 兜底回收卡死在 PLANNING 的任务。</p>
 */
public interface PlannerDecomposeAsyncService {

    /**
     * 异步执行拆解：选 Planner → 渲染 Prompt → 调 LLM → 解析校验 → 批量落库草案
     * → 依赖序号回写真实 id → 记录 {@code task_plan_generated}。
     *
     * <p>幂等防御：仅当任务仍处 PLANNING 时才执行，否则直接跳过（覆盖超时回收后
     * 慢线程迟到、重复提交等边界）。任何失败都会回退 PENDING 并记录 timeline。</p>
     *
     * @param taskId 任务 ID（调用前已由同步守卫推进至 PLANNING）
     */
    void executeDecompose(Long taskId);

    /**
     * 依赖校验（V27）：序号越界/自引用即拒，再用 Kahn 拓扑排序做环检测，
     * 成环整批拒绝（抛 BizException → 拆解失败回退 PENDING 可重拆）。
     *
     * <p>序号为 1-based（指向同批草案中的第 N 条）；dependsOn 为 null/空视为无依赖。</p>
     */
    void validateDependencies(List<PlanDraftItem> items);
}
