package com.helloai.core.planner.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.Data;

import java.util.List;

/**
 * Planner 平台内自动拆解服务（需求 → 子任务草案 → 用户确认 → 进入既有分发链）。
 *
 * <p>职责边界（对齐 §6.3 分层红线：编排逻辑收口在 core，Controller 只做薄转发）：</p>
 * <ul>
 *     <li>{@link #decompose(Long)}：同步守卫（校验 + CAS 推进 Task → PLANNING）后提交
 *         {@link PlannerDecomposeAsyncService} 异步执行 LLM 拆解，立即返回空列表；
 *         草案产出与失败回退均由异步段收敛，卡死任务由 PlanningTimeoutTask 兜底回收。</li>
 *     <li>{@link #listDrafts(Long)}：查看草案列表。</li>
 *     <li>{@link #confirmPlan(Long)}：草案批量转正（→ PENDING），Task → IN_PROGRESS，
 *         按 {@code autoAssignOnCreate} 配置触发既有自动分发链（与手工创建子任务同构）。</li>
 *     <li>{@link #rejectPlan(Long)}：草案翻 CANCELLED（保留审计），Task 回退 PENDING 可重新拆解。</li>
 * </ul>
 *
 * <p>草案态 {@code PENDING_PLAN_REVIEW} 对 claim/assignNext/自动重派/补偿定时任务全部不可见
 * （它们只认 PENDING 等状态），无需额外隔离逻辑。</p>
 *
 * <p>confirm/reject 刻意不加类级事务：逐条 changeStatus 各自独立事务（与既有风格一致），
 * 中途失败可重入——已转正/已取消的子任务不再出现在草案列表，重调即续做剩余部分。</p>
 */
public interface PlannerAnalysisService {

    /**
     * 触发平台内自动拆解（同步守卫）。
     *
     * <p>只做校验与状态推进：校验通过后 CAS 推进 PLANNING、记录
     * {@code task_plan_async_submitted}，随后把 LLM 拆解段提交
     * {@link PlannerDecomposeAsyncService} 异步执行并立即返回空列表（拆解异步化改造，
     * HTTP 线程不再等待 LLM）。异步段通过 timeline 收敛结果，前端轮询草案；
     * 卡死任务由 PlanningTimeoutTask 兜底回收。</p>
     *
     * @return 恒为空列表（API 契约保持不变，草案经 {@link #listDrafts(Long)} 轮询获取）
     */
    List<SubTask> decompose(Long taskId);

    /** 查看指定任务的草案列表（PENDING_PLAN_REVIEW），按依赖拓扑排序为正序（根在前）。 */
    List<SubTask> listDrafts(Long taskId);

    /**
     * 确认草案：全部 PENDING_PLAN_REVIEW → PENDING，Task → IN_PROGRESS。
     *
     * <p>随后按 {@code helloai.dispatch.auto-assign-on-create} 配置决定是否
     * 逐条走 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto} 自动分配，
     * 与 SubTaskController 手工创建子任务的分发路径完全同构。</p>
     *
     * @return 转正后的子任务列表
     */
    List<SubTask> confirmPlan(Long taskId);

    /**
     * 拒绝草案：全部 PENDING_PLAN_REVIEW → CANCELLED（保留审计），Task 回退 PENDING 可重新拆解。
     *
     * @return 被取消的草案数量
     */
    int rejectPlan(Long taskId);

    /** LLM 结构化输出条目（未知字段容忍，避免 LLM 多给字段导致整批失败）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    class PlanDraftItem {
        private String title;
        private String content;
        private String deliverable;
        private String acceptance;
        private String priority;
        /** 依赖的同批草案序号（1-based，V27）；空/null=无依赖。 */
        private List<Integer> dependsOn;
    }
}
