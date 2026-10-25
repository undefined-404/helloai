package com.helloai.core.planner.service;

import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;
import com.helloai.core.task.port.SubTaskView;

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
 *
 * <p>`TD-9`：单条草案的 expand / regenerate 走<b>同步</b>执行（用户等待结果，不走
 * {@code plannerDecomposeExecutor}），复用本类全部私有能力（提示词渲染 / 技能过滤 /
 * Kahn 校验 / 依赖回写）。</p>
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
     * 依赖校验：序号越界/自引用即拒，再用 Kahn 拓扑排序做环检测，
     * 成环整批拒绝（抛 BizException → 拆解失败回退 PENDING 可重拆）。
     *
     * <p>序号为 1-based（指向同批草案中的第 N 条）；dependsOn 为 null/空视为无依赖。</p>
     */
    void validateDependencies(List<PlanDraftItem> items);

    /**
     * `TD-9` 单条草案细拆（expand）：把一条草案按 FINE 粒度拆成 N 条子草案。
     *
     * <p>输入 = 该条草案四要素 + 任务上下文快照（{@code DecomposeContextSnapshotRenderer}）；
     * LLM 产出 N 条（组内 1-based 依赖），Kahn 校验后落库并回写依赖，<b>原草案被删除</b>
     * （逻辑删），由 N 条子草案替换。被其他草案引用（dependsOn 指向它）的草案
     * <b>拒绝展开</b>（引用会成幽灵依赖，fail-close）。</p>
     *
     * @param subTaskId 草案子任务 ID（须 PENDING_PLAN_REVIEW）
     * @return 展开后的子草案列表（含落库回填的真实 id）
     * @throws BizException 非草案态 / 被引用 / LLM 失败 / 校验失败（原草案不被破坏）
     */
    List<SubTaskView> expandById(Long subTaskId);

    /**
     * `TD-9` 单条草案重生成（regenerate）：同粒度重写一条草案的四要素。
     *
     * <p><b>不变量白名单</b>：只允许改 {@code content} / {@code deliverable} /
     * {@code acceptance} / {@code acceptanceKind}；<b>{@code title} / {@code id} /
     * {@code dependsOn} 一律不动</b>（改了审计线索就断了——外部引用、依赖图、时间线全对不上）。
     * LLM 输出中的 title/dependsOn 被忽略，取原草案值。</p>
     *
     * @param subTaskId 草案子任务 ID（须 PENDING_PLAN_REVIEW）
     * @return 重生成后的草案快照
     * @throws BizException 非草案态 / LLM 失败 / 校验失败（原草案不被破坏）
     */
    SubTaskView regenerateById(Long subTaskId);
}
