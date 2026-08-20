package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.shared.util.SubTaskDependencyOrder;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.TaskBaseline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Planner 平台内自动拆解服务实现。
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
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerAnalysisServiceImpl implements PlannerAnalysisService {

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final SubTaskMapper subTaskMapper;
    private final PlannerDecomposeAsyncService plannerDecomposeAsyncService;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final TaskRunningSpecService taskRunningSpecService;

    // ══════════════════════════════════════════════════════════════
    //  拆解：Task → PENDING_PLAN_REVIEW 草案
    // ══════════════════════════════════════════════════════════════

    /**
     * 触发平台内自动拆解（同步守卫）。
     *
     * <p>只做校验与状态推进：校验通过后 CAS 推进 PLANNING、记录
     * {@code task_plan_async_submitted}，随后把 LLM 拆解段提交
     * {@link PlannerDecomposeAsyncService} 异步执行并立即返回空列表——
     * HTTP 线程不再等待 LLM（拆解异步化改造）。异步段通过
     * {@code task_plan_generated}/{@code task_plan_failed} timeline 收敛结果，
     * 前端轮询草案；卡死任务由 PlanningTimeoutTask 兜底回收。</p>
     *
     * @return 恒为空列表（API 契约 {@code List<SubTask>} 保持不变，草案经 listDrafts 轮询获取）
     */
    @Override
    public List<SubTask> decompose(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的任务才能触发拆解: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        // 已有非 CANCELLED 子任务不再拆分（对齐 openMoss 防重复拆分原则）
        long existing = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .ne(SubTask::getStatus, SubTaskStatus.CANCELLED)
                .count();
        if (existing > 0) {
            throw new BizException("任务已存在 " + existing + " 个子任务，不允许重复拆解；"
                    + "如需重新规划请先取消既有子任务");
        }
        // §6.100 重新拆解前物理清理 CANCELLED 旧草案：拒绝计划后残留行携带幽灵依赖
        // （回写时引用未落库 ID，导致后续 PENDING 子任务依赖校验永不就绪），
        // 直接物理删除避免新草案再次被污染（该场景草案从未执行，无关联执行记录）
        long cancelled = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .eq(SubTask::getStatus, SubTaskStatus.CANCELLED)
                .count();
        if (cancelled > 0) {
            log.info("重新拆解前物理清理 CANCELLED 旧草案: taskId={}, count={}", taskId, cancelled);
            subTaskMapper.physicalDeleteByTaskId(taskId);
        }

        // CAS 推进 PENDING → PLANNING，防并发重复拆解
        boolean cas = taskService.lambdaUpdate()
                .eq(Task::getId, taskId)
                .eq(Task::getStatus, TaskStatus.PENDING)
                .set(Task::getStatus, TaskStatus.PLANNING)
                .update();
        if (!cas) {
            throw new BizException("任务正在被其他请求拆解中，请稍后查看草案: taskId=" + taskId);
        }

        // 记录异步提交 timeline：HTTP 线程到此即返回，LLM 拆解转由异步段推进
        taskTimelineService.recordEvent(taskId, null, "task_plan_async_submitted",
                AgentRole.PLANNER, null, Map.of("executor", "plannerDecomposeExecutor"));

        // 跨类调用注入的异步服务，确保 @Async 代理生效（禁止同类自调用）；
        // 专用线程池队列满时按 AbortPolicy 拒绝，回退 PENDING 并提示稍后重试
        try {
            plannerDecomposeAsyncService.executeDecompose(taskId);
        } catch (TaskRejectedException e) {
            taskService.lambdaUpdate()
                    .eq(Task::getId, taskId)
                    .eq(Task::getStatus, TaskStatus.PLANNING)
                    .set(Task::getStatus, TaskStatus.PENDING)
                    .update();
            log.warn("拆解提交被线程池拒绝，已回退 PENDING: taskId={}", taskId);
            throw new BizException("拆解排队已满，请稍后重试");
        }
        log.info("任务拆解已提交异步执行: taskId={}", taskId);
        return Collections.emptyList();
    }

    // ══════════════════════════════════════════════════════════════
    //  草案查看 / 确认 / 拒绝
    // ══════════════════════════════════════════════════════════════

    /** 查看指定任务的草案列表（PENDING_PLAN_REVIEW），按依赖拓扑排序为正序（根在前）。 */
    @Override
    public List<SubTask> listDrafts(Long taskId) {
        if (taskService.getById(taskId) == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        return orderByDependency(subTaskService.list(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0).getRecords());
    }

    /**
     * 确认草案：全部 PENDING_PLAN_REVIEW → PENDING，Task → IN_PROGRESS。
     *
     * <p>随后按 {@code helloai.dispatch.auto-assign-on-create} 配置决定是否
     * 逐条走 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto} 自动分配，
     * 与 SubTaskController 手工创建子任务的分发路径完全同构。</p>
     *
     * @return 转正后的子任务列表
     */
    @Override
    public List<SubTask> confirmPlan(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PLANNING) {
            throw new BizException("只有 PLANNING 状态的任务才能确认草案: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        List<SubTask> drafts = orderByDependency(subTaskService.list(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0).getRecords());
        if (drafts.isEmpty()) {
            // 幂等恢复：若 PLANNING 草案已为空但存在已确认（PENDING 且 context 含 planConfirmedAt）的子任务，
            // 说明前次 confirmPlan 的 SubTask 状态变更已提交但 Task 状态更新未生效，允许恢复。
            List<SubTask> alreadyConfirmed = recoverAlreadyConfirmed(taskId);
            if (alreadyConfirmed.isEmpty()) {
                throw new BizException("任务没有待确认的规划草案: taskId=" + taskId);
            }
            log.warn("检测到部分确认状态（SubTask 已转正但 Task 未推进），自动恢复: taskId={}, count={}",
                    taskId, alreadyConfirmed.size());
            return finishConfirm(task, alreadyConfirmed);
        }

        for (SubTask draft : drafts) {
            // 任务级 SLA 下发 deadline。必须先持久化再 changeStatus——
            // changeStatus 内部按 id 重查库后全字段 updateById，未落库的 deadline 会被覆盖丢失。
            if (task.getSlaMinutes() != null && task.getSlaMinutes() > 0) {
                draft.setDeadline(OffsetDateTime.now().plusMinutes(task.getSlaMinutes()));
                subTaskService.updateById(draft);
            }
            subTaskService.changeStatus(draft.getId(), SubTaskStatus.PENDING, null,
                    Map.of("planConfirmedAt", OffsetDateTime.now().toString()));
        }
        return finishConfirm(task, drafts);
    }

    /**
     * 完成确认收尾：推进 Task 状态、初始化 RunningSpec、记录 timeline、触发自动分发。
     */
    private List<SubTask> finishConfirm(Task task, List<SubTask> confirmed) {
        task.setStatus(TaskStatus.IN_PROGRESS);
        boolean updated = taskService.updateById(task);
        if (!updated) {
            throw new BizException("任务状态推进失败（updateById 返回 false），请重试: taskId=" + task.getId());
        }

        // 初始化 Task Running Spec Baseline
        try {
            Long plannerAgentId = extractPlannerAgentId(confirmed);
            TaskBaseline baseline = TaskBaseline.builder()
                    .goal(buildBaselineGoal(task))
                    .constraints("平台约束：子任务按 DAG 依赖顺序执行，下游须参考上游产出")
                    .raw(buildBaselineRaw(confirmed))
                    .createdBy(plannerAgentId)
                    .createdAt(OffsetDateTime.now().toString())
                    .build();
            taskRunningSpecService.initialize(task.getId(), baseline);
        } catch (Exception e) {
            log.warn("TaskRunningSpec Baseline 初始化失败（不阻断草案确认）: taskId={}, err={}",
                    task.getId(), e.getMessage());
        }

        Long taskId = task.getId();
        taskTimelineService.recordEvent(taskId, null, "task_plan_confirmed",
                AgentRole.PLANNER, null, Map.of("subTaskCount", confirmed.size()));

        // 事务外触发自动分发（分发链内部有独立事务与事件），单条失败不阻断其余。
        // 确认草案是用户显式启动内循环的动作，不受 auto-assign-on-create
        // （任务创建即分发）开关控制；否则开关关闭时只能等孤儿扫描兜底，
        // 内循环无法自动运转。ready 守卫会自动拦住依赖未就绪的节点。
        for (SubTask draft : confirmed) {
            try {
                subTaskDispatchService.dispatchPendingSubTaskAuto(draft.getId(), AgentRole.EXECUTOR);
            } catch (Exception e) {
                log.warn("草案转正后自动分发失败（保持 PENDING 等待兜底任务）: subTaskId={}, err={}",
                        draft.getId(), e.getMessage());
            }
        }
        log.info("任务规划草案已确认: taskId={}, subTaskCount={}", taskId, confirmed.size());
        return confirmed.stream().map(d -> subTaskService.getById(d.getId())).toList();
    }

    /**
     * 幂等恢复：查找已确认（PENDING 状态且 context 含 planConfirmedAt）的子任务。
     * <p>用于处理前次 confirmPlan 的 SubTask 状态变更已提交但 Task 状态更新未生效的场景。</p>
     */
    private List<SubTask> recoverAlreadyConfirmed(Long taskId) {
        List<SubTask> pending = subTaskService.list(
                taskId, SubTaskStatus.PENDING, null, null, 0).getRecords();
        return pending.stream()
                .filter(st -> st.getContext() != null
                        && st.getContext().containsKey("planConfirmedAt"))
                .toList();
    }

    /**
     * 拒绝草案：全部 PENDING_PLAN_REVIEW → CANCELLED（保留审计），Task 回退 PENDING 可重新拆解。
     *
     * @return 被取消的草案数量
     */
    @Override
    public int rejectPlan(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException("任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PLANNING) {
            throw new BizException("只有 PLANNING 状态的任务才能拒绝草案: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        List<SubTask> drafts = subTaskService.list(
                taskId, SubTaskStatus.PENDING_PLAN_REVIEW, null, null, 0).getRecords();

        for (SubTask draft : drafts) {
            subTaskService.changeStatus(draft.getId(), SubTaskStatus.CANCELLED, null,
                    Map.of("planRejectedAt", OffsetDateTime.now().toString()));
        }
        task.setStatus(TaskStatus.PENDING);
        taskService.updateById(task);
        taskTimelineService.recordEvent(taskId, null, "task_plan_rejected",
                AgentRole.PLANNER, null, Map.of("cancelledCount", drafts.size()));
        log.info("任务规划草案已拒绝: taskId={}, cancelledCount={}", taskId, drafts.size());
        return drafts.size();
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /**
     * 按依赖拓扑排序（稳定 Kahn 入度法）：无前置依赖的根节点排在前，
     * 依赖项总在其依赖之后，使草案审阅与分发呈正序（1→N，dependsOn 恒指向更靠前的行），
     * 符合多数人的阅读与执行习惯。
     *
     * <p>实现已提炼为公共工具 {@link SubTaskDependencyOrder}（交付物 zip
     * 聚合复用同一排序语义），本方法保留为委托入口，行为不变。</p>
     */
    private List<SubTask> orderByDependency(List<SubTask> drafts) {
        return SubTaskDependencyOrder.orderByDependency(drafts);
    }

    /** 构建 Baseline goal：任务标题 + 描述。 */
    private String buildBaselineGoal(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题: ").append(task.getTitle());
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            sb.append("\n任务描述: ").append(task.getDescription());
        }
        return sb.toString();
    }

    /** 构建 Baseline raw：子任务 DAG 结构摘要。 */
    private String buildBaselineRaw(List<SubTask> drafts) {
        StringBuilder sb = new StringBuilder();
        sb.append("子任务 DAG 结构（共 ").append(drafts.size()).append(" 个）：\n");
        for (int i = 0; i < drafts.size(); i++) {
            SubTask d = drafts.get(i);
            sb.append(i + 1).append(". ").append(d.getTitle());
            if (d.getDependsOn() != null && !d.getDependsOn().isEmpty()) {
                sb.append(" [依赖: ").append(d.getDependsOn().size()).append("个前置]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 从草案 context 中提取 Planner Agent ID。 */
    private Long extractPlannerAgentId(List<SubTask> drafts) {
        if (drafts.isEmpty()) {
            return null;
        }
        Map<String, Object> ctx = drafts.get(0).getContext();
        if (ctx != null && ctx.get("plannerAgentId") instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
