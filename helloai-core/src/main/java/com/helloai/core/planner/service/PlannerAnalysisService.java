package com.helloai.core.planner.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import lombok.Data;

import java.util.List;

/**
 * Planner 平台内自动拆解服务（需求 → 子任务草案 → 用户确认 → 进入既有分发链）。
 *
 * <p>职责边界（对齐 §6.3 分层红线：编排逻辑收口在 core，Controller 只做薄转发）：</p>
 * <ul>
 *     <li>{@link #decompose(Long)}：CAS 推进 Task → PLANNING，选平台内 API_KEY_LLM Planner
 *         调 LLM 结构化输出，批量落库 {@code PENDING_PLAN_REVIEW} 草案；失败回退 PENDING。</li>
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
     * 触发平台内自动拆解。
     *
     * <p>不加事务：LLM 调用耗时较长，不能占用数据库事务；草案批量落库走
     * {@link SubTaskService#saveBatch(java.util.Collection)}（ServiceImpl 自带事务，原子提交）。
     * 任何失败路径都会把 Task 从 PLANNING 回退 PENDING 并记录 timeline。</p>
     *
     * @return 落库后的草案列表
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

    /**
     * 依赖校验（V27）：序号越界/自引用即拒，再用 Kahn 拓扑排序做环检测，
     * 成环整批拒绝（抛 BizException → decompose 失败回退 PENDING 可重拆）。
     *
     * <p>序号为 1-based（指向同批草案中的第 N 条）；dependsOn 为 null/空视为无依赖。</p>
     */
    void validateDependencies(List<PlanDraftItem> items);

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
