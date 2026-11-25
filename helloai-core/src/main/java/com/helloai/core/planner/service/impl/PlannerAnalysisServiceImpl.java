package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.policy.SemanticContractGate;
import com.helloai.core.planner.port.PlanDraftView;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.planner.service.PlanDraftRoundService;
import com.helloai.core.planner.service.PlanDraftService;
import com.helloai.core.planner.support.PlanDraftFindingsStore;
import com.helloai.core.planner.support.PlanDraftViewMapper;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.support.PlanGateRunner;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.port.UncertaintyDraft;
import com.helloai.core.task.port.UncertaintyView;
import com.helloai.core.task.util.SubTaskDependencyOrder;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskDerivationService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.TaskBaseline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 *     <li>{@link #listDraftsFromStaging(Long)}：查看草案列表（暂存区唯一读路）。</li>
 *     <li>{@link #confirmPlan(Long)}：草案批量转正（→ PENDING），Task → IN_PROGRESS，
 *         按 {@code autoAssignOnCreate} 配置触发既有自动分发链（与手工创建子任务同构）。</li>
 *     <li>{@link #rejectPlan(Long)}：作废暂存区整轮（DISCARDED），Task 回退 PENDING 可重新拆解。</li>
 * </ul>
 *
 * <p>草案只存在于 {@code task_plan_draft}，对 claim/assignNext/自动重派/补偿定时任务天然不可见
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
    private final PlannerDecomposeAsyncService plannerDecomposeAsyncService;
    private final TaskTimelineService taskTimelineService;
    private final PlanGateRunner planGateRunner;
    /**
     * 语义门（`G4_UNSATISFIABLE_CONTRACT`）：确认前唯一一次模型调用。
     *
     * <p>只挂在这里、不挂拆解/展开路径：那两个是生成与改造类操作，草案本来就允许带诊断存在；
     * 确认是唯一放行口，也是唯一值得付一次模型调用的时点（设计 §6.2）。</p>
     */
    private final SemanticContractGate semanticContractGate;
    private final SubTaskDispatchService subTaskDispatchService;
    private final TaskRunningSpecService taskRunningSpecService;
    /** 拆解租约时长（{@code helloai.planner.decompose.planning-timeout-minutes}）。 */
    private final PlannerDecomposeProperties plannerDecomposeProperties;

    /**
     * 拆解尝试登记（`TD-16b`）：`decompose` 经它完成「状态变更 + Outbox 意图」的原子写。
     *
     * <p>放在本类而非直接写 {@code taskService.beginDecompose} 之后补一次 insert，
     * 是因为两次写必须同事务；事务边界收在 {@code PlanAttemptService#beginDecompose}
     * （见该方法的 Javadoc）。</p>
     */
    private final PlanAttemptService planAttemptService;
    /** 暂存区轮次：拒绝草案时整轮作废（否则下一轮 code 撞唯一索引）。 */
    private final PlanDraftRoundService planDraftRoundService;
    /** `TD-16d-4` 读侧：草案列表从暂存区取（无当前轮时回落 sub_task）。 */
    private final PlanDraftMapper planDraftMapper;
    /** `AD-5a-3b-1`：确认判定的输入以暂存区为准（执行表行仍是转正目标）。 */
    private final PlanDraftService planDraftService;
    /** `AD-5a-3b-2b`：暂存区路径的门禁结论写 `task_plan_draft.context`。 */
    private final PlanDraftFindingsStore planDraftFindingsStore;
    /** `ADR-008` 草案期派生：建独立 Task `Y`（只依赖 task 域契约，不触碰 task.mapper/entity）。 */
    private final TaskDerivationService taskDerivationService;

    /**
     * 派生会话的关闭口（`V150`）：父任务「拒绝重拆」级联取消派生任务时，同步关闭其派生会话。
     *
     * <p>放在 planner 侧而非 task 域：task(2) → planner(0) 是反向依赖，task 域不能反查会话。</p>
     */
    private final RequirementConversationService conversationService;

    // ══════════════════════════════════════════════════════════════
    //  拆解：Task → 暂存区草案（AWAITING_PLAN_CONFIRM）
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
     * @return 恒为空列表（API 契约 {@code List<SubTaskView>} 保持不变，草案经 listDrafts 轮询获取）
     */
    @Override
    public List<SubTaskView> decompose(Long taskId) {
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException(400, "任务不存在: " + taskId);
        }
        if (task.status() != TaskStatus.PENDING) {
            throw new BizException(400, "只有 PENDING 状态的任务才能触发拆解: taskId=" + taskId
                    + ", status=" + task.status());
        }
        // 已有非 CANCELLED 子任务不再拆分（对齐 openMoss 防重复拆分原则）
        long existing = subTaskService.countByTaskIdExcludingStatus(taskId, SubTaskStatus.CANCELLED);
        if (existing > 0) {
            throw new BizException(400, "任务已存在 " + existing + " 个子任务，不允许重复拆解；"
                    + "如需重新规划请先取消既有子任务");
        }
        // §6.100 重新拆解前物理清理 CANCELLED 旧草案：拒绝计划后残留行携带幽灵依赖
        // （回写时引用未落库 ID，导致后续 PENDING 子任务依赖校验永不就绪），
        // 直接物理删除避免新草案再次被污染。
        //
        // 删除条件从「该任务下全部子任务」收窄为「CANCELLED 且从未执行」
        // （`physicalDeleteNeverExecutedCancelled`）。原实现依赖本方法上方那句
        // `countByTaskIdExcludingStatus` 保证「此刻全是 CANCELLED」——那是**调用方不变量**，
        // 换个调用点就失效；收窄后写在 SQL 里，对任何调用方都成立。
        // 上面注释原写「该场景草案从未执行」，但那一直是假设，现已落成 NOT EXISTS 条件。
        long cancelled = subTaskService.countByTaskIdAndStatus(taskId, SubTaskStatus.CANCELLED);
        if (cancelled > 0) {
            log.info("重新拆解前物理清理 CANCELLED 旧草案: taskId={}, count={}", taskId, cancelled);
            // §7.1：跨域物理删除经 task 域 Service 收口，不直捅 SubTaskMapper
            int deleted = subTaskService.physicalDeleteNeverExecutedCancelled(taskId);
            if (deleted < cancelled) {
                // 有执行痕迹的行被保留：这些行取消前跑过，删掉只会留下指向不存在子任务的
                // 时间线 / 审查记录（soft reference，无外键，DB 不拦也不回收）。
                log.warn("重新拆解前保留 {} 条有执行痕迹的 CANCELLED 旧草案（删 {} / 共 {}）: taskId={}",
                        cancelled - deleted, deleted, cancelled, taskId);
                taskTimelineService.recordEvent(taskId, null,
                        TimelineEventCode.TASK_PLAN_CANCELLED_DRAFT_RETAINED.code(),
                        AgentRole.PLANNER, null,
                        Map.of("cancelled", cancelled, "deleted", deleted,
                                "retained", cancelled - deleted));
            }
        }

        // CAS 推进 PENDING → PLANNING，同时写拆解租约与 attemptId（`V146`）：三者必须同一条
        // UPDATE，否则会留「已 PLANNING 但租约/attemptId 为空」的窗口——租约为空会被巡检漏检
        // （永久卡死），attemptId 为空会让幂等键失效（异步段各生成新 UUID，唯一索引永不冲突）。
        //
        // 2026-11-08（`TD-16b`）：改由 `PlanAttemptService#beginDecompose` 收口，
        // 它在**同一事务**里再登记 `task_plan_attempt`（Outbox 意图）。这样「状态变更」与
        // 「命令落库」原子——进程崩在随后立刻发生的 @Async 之前，库里仍有 PENDING 尝试，
        // 由 `PlanAttemptDispatchTask` 兜底接手。「不丢」的保证从「靠进程活着」变成「靠库里有记录」。
        String attemptId = planAttemptService.beginDecompose(taskId,
                plannerDecomposeProperties.getPlanningTimeoutMinutes());
        if (attemptId == null) {
            throw new BizException(400, "任务正在被其他请求拆解中，请稍后查看草案: taskId=" + taskId);
        }
        log.info("拆解已进入 PLANNING: taskId={}, attemptId={}", taskId, attemptId);

        // 记录异步提交 timeline：HTTP 线程到此即返回，LLM 拆解转由异步段推进
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_ASYNC_SUBMITTED.code(),
                AgentRole.PLANNER, null, Map.of("executor", "plannerDecomposeExecutor"));

        // 跨类调用注入的异步服务，确保 @Async 代理生效（禁止同类自调用）；
        // 专用线程池队列满时按 AbortPolicy 拒绝，回退 PENDING 并提示稍后重试
        try {
            plannerDecomposeAsyncService.executeDecompose(taskId);
        } catch (TaskRejectedException e) {
            // 带 attemptId 回退：只比状态时，若本轮租约已被巡检回收、用户已重新拆解，
            // 这里的回退会把新一轮的 PLANNING 推回 PENDING。
            taskService.casStatusForAttempt(taskId, TaskStatus.PLANNING, TaskStatus.PENDING, attemptId);
            log.warn("拆解提交被线程池拒绝，已回退 PENDING: taskId={}, attemptId={}", taskId, attemptId);
            throw new BizException(400, "拆解排队已满，请稍后重试");
        }
        log.info("任务拆解已提交异步执行: taskId={}", taskId);
        return Collections.emptyList();
    }

    // ══════════════════════════════════════════════════════════════
    //  草案查看 / 确认 / 拒绝
    // ══════════════════════════════════════════════════════════════

    /**
     * `TD-16d-4` 读侧换源：从暂存区 `task_plan_draft` 读当前轮草案列表。
     *
     * <p>`AD-5a-4` 存量迁入后 `sub_task` 不再承载草案，本方法是**唯一读路**：无当前轮时
     * 返回空列表，不再回落执行表。返回视图的 id 为暂存区行 id。</p>
     */
    @Override
    public List<SubTaskView> listDraftsFromStaging(Long taskId) {
        if (taskService.getView(taskId) == null) {
            throw new BizException(400, "任务不存在: " + taskId);
        }
        int round = planDraftMapper.findMaxActiveRound(taskId);
        if (round <= 0) {
            return List.of();
        }
        List<Long> rowIds = planDraftMapper.listActiveIdsByRound(taskId, round);
        if (rowIds.isEmpty()) {
            return List.of();
        }
        Map<Long, PlanDraft> byId = new HashMap<>();
        for (PlanDraft d : planDraftMapper.selectBatchIds(rowIds)) {
            byId.put(d.getId(), d);
        }
        List<PlanDraftView> views = new ArrayList<>(rowIds.size());
        for (Long rowId : rowIds) {
            PlanDraft d = byId.get(rowId);
            if (d != null) {
                views.add(PlanDraftViewMapper.toView(d));
            }
        }
        return PlanDraftViewMapper.toSubTaskViews(views);
    }
    /**
     * 确认草案：暂存区当前轮物化为执行行（PENDING/AWAITING_DERIVED/DONE），Task → IN_PROGRESS。
     *
     * <p>随后按 {@code helloai.dispatch.auto-assign-on-create} 配置决定是否
     * 逐条走 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto} 自动分配，
     * 与 SubTaskController 手工创建子任务的分发路径完全同构。</p>
     *
     * @return 转正后的子任务列表
     */
    @Override
    public List<SubTaskView> confirmPlan(Long taskId) {
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException(400, "任务不存在: " + taskId);
        }
        // `V146` 起「草案待确认」是独立状态：PLANNING 只表示拆解中（机器在跑），
        // 两者混用会让「拆解中」与「等你确认」在界面上无法区分，也是超时误杀的根因。
        if (task.status() != TaskStatus.AWAITING_PLAN_CONFIRM) {
            throw new BizException(400, (task.status() == TaskStatus.PLANNING
                    ? "拆解仍在进行中，请等待草案产出后再确认"
                    : "当前状态不可确认草案")
                    + ": taskId=" + taskId + ", status=" + task.status());
        }
        List<PlanDraftView> staged = planDraftService.listByTask(taskId);
        List<SubTaskView> drafts = PlanDraftViewMapper.toSubTaskViews(staged);
        if (drafts.isEmpty()) {
            // 幂等恢复：若草案已为空但存在已确认的子任务，说明前次 confirmPlan 的行状态已提交
            // 但 Task 状态更新未生效，允许恢复。
            List<SubTaskView> alreadyConfirmed = recoverAlreadyConfirmed(taskId);
            if (alreadyConfirmed.isEmpty()) {
                throw new BizException(400, "任务没有待确认的规划草案: taskId=" + taskId);
            }
            log.warn("检测到部分确认状态（SubTaskView 已转正但 Task 未推进），自动恢复: taskId={}, count={}",
                    taskId, alreadyConfirmed.size());
            return finishConfirm(task, alreadyConfirmed);
        }

        // 确认是**唯一放行口**：门禁结论直接写暂存区，再按暂存区为源物化执行行。
        drafts = recheckStaged(task, staged, drafts);
        // 复核与物化之间仍有窗口：按内容指纹重读暂存区，变了就整批拒绝（findings 写回不算内容变更）
        drafts = reloadStagedAfterRecheck(taskId, drafts);

        Map<String, List<String>> depsByCode = new HashMap<>();
        for (PlanDraftView d : staged) {
            if (d.code() != null) {
                depsByCode.put(d.code(), d.dependsOnCodes() == null ? List.of() : d.dependsOnCodes());
            }
        }
        List<SubTaskDraft> materialize = new ArrayList<>(drafts.size());
        for (SubTaskView v : drafts) {
            materialize.add(toSubTaskDraft(v));
        }
        List<SubTaskView> confirmed = subTaskService.materializeFromStaging(
                taskId, materialize, depsByCode, task.slaMinutes());
        if (confirmed.size() != drafts.size()) {
            throw new BizException("草案物化条数不符: expected=" + drafts.size()
                    + ", actual=" + confirmed.size() + ", taskId=" + taskId);
        }
        return finishConfirm(task, confirmed);
    }

    /** `AD-5a-3b-2b`：暂存区路径的确认前复核（门禁输入为暂存区快照，findings 写暂存区）。 */
    private List<SubTaskView> recheckStaged(TaskView task, List<PlanDraftView> staged, List<SubTaskView> views) {
        Long taskId = task.id();
        Long plannerAgentId = extractPlannerAgentId(views);
        DraftGateChecker.Result rule = planGateRunner.evaluate(views, task.taskArchetype());
        if (rule.hasBlocking()) {
            planDraftFindingsStore.persist(taskId, staged, rule);
            planGateRunner.recordTimeline(taskId, plannerAgentId, "confirm", views, rule);
            throw new BizException(400, planGateRunner.describe(views, rule.blocking())
                    + "；请在草案页修正后重试确认");
        }
        DraftGateChecker.Result merged = planGateRunner.merge(rule,
                semanticContractGate.judge(views, plannerAgentId).findings());
        if (!merged.isEmpty()) {
            planDraftFindingsStore.persist(taskId, staged, merged);
            planGateRunner.recordTimeline(taskId, plannerAgentId, "confirm", views, merged);
        }
        if (merged.hasBlocking()) {
            throw new BizException(400, planGateRunner.describe(views, merged.blocking())
                    + "；请在草案页修正后重试确认");
        }
        return views;
    }

    /**
     * `AD-5b`：复核后重读暂存区，按**门禁实际读取的字段**比对指纹。
     *
     * <p>旧实现用 `sub_task.update_time` 探测「确认期间被改」；草案不再落执行表后，
     * 基准换成暂存区内容。只看内容字段：`gateFindings` 写回会刷新 context/update_time，
     * 那是复核自己的写，不算漂移。返回重读后的视图供物化使用（含刚写入的 findings）。</p>
     */
    private List<SubTaskView> reloadStagedAfterRecheck(Long taskId, List<SubTaskView> gated) {
        List<PlanDraftView> latest = planDraftService.listByTask(taskId);
        List<SubTaskView> latestViews = PlanDraftViewMapper.toSubTaskViews(latest);
        Map<String, String> before = gateFingerprints(gated);
        Map<String, String> after = gateFingerprints(latestViews);
        if (!before.equals(after)) {
            throw new BizException(400, "草案在确认期间被修改，门禁结论已过期，请重新确认: taskId=" + taskId);
        }
        return latestViews;
    }

    private static Map<String, String> gateFingerprints(List<SubTaskView> views) {
        Map<String, String> out = new HashMap<>();
        if (views == null) {
            return out;
        }
        for (SubTaskView v : views) {
            if (v == null) {
                continue;
            }
            String key = v.draftCode() != null && !v.draftCode().isBlank()
                    ? v.draftCode() : "id:" + v.id();
            out.put(key, String.join("",
                    String.valueOf(v.title()), String.valueOf(v.content()),
                    String.valueOf(v.deliverable()), String.valueOf(v.acceptance()),
                    String.valueOf(v.acceptanceKind()), String.valueOf(v.constraints()),
                    String.valueOf(v.dependsOn())));
        }
        return out;
    }

    /**
     * 完成确认收尾：推进 Task 状态、初始化 RunningSpec、记录 timeline、触发自动分发。
     *
     * <p><b>本方法刻意留在 T2 事务之外（`TD-16e`）</b>：{@link #confirmDrafts} 已把草案转正
     * 收进一个事务并提交；这里两步都是 {@code REQUIRES_NEW} 且**写同一批 `sub_task` 行**
     * （{@code planGateRunner.clear} 走 {@code updateContextInNewTx}），若塞进 T2 事务里，
     * 内层 REQUIRES_NEW 要等外层释放行锁、外层又在等内层返回 ⇒ 连接级死锁
     * （真机 90 秒挂死同型）。放在提交后执行，两者各拿各的锁。</p>
     *
     * <p>代价是「草案已 PENDING 但任务还没 IN_PROGRESS」的窗口。这个方向是安全的：
     * 派发只在本方法末尾触发，窗口内没有任何消费者；重试 confirm 会因草案为空走
     * {@link #recoverAlreadyConfirmed} 补齐——与原来「一半转正」的不可恢复状态不同。</p>
     */
    /** `AD-5a-3b-2`：门禁快照 → 物化物料（保留 code / seq / context / 不确定性）。 */
    private static com.helloai.core.task.port.SubTaskDraft toSubTaskDraft(SubTaskView v) {
        List<com.helloai.core.task.port.UncertaintyDraft> uncertainties = List.of();
        if (v.uncertainties() != null && !v.uncertainties().isEmpty()) {
            uncertainties = v.uncertainties().stream()
                    .map(u -> new com.helloai.core.task.port.UncertaintyDraft(
                            u.kind(), u.note(), u.ref(), u.blockedBy()))
                    .toList();
        }
        List<com.helloai.core.task.port.ConstraintRefDraft> refs = List.of();
        if (v.constraintRefs() != null && !v.constraintRefs().isEmpty()) {
            refs = v.constraintRefs().stream()
                    .map(r -> r == null ? null : new com.helloai.core.task.port.ConstraintRefDraft(
                            r.kind(), r.ref(), r.scope()))
                    .filter(r -> r != null)
                    .toList();
        }
        return new com.helloai.core.task.port.SubTaskDraft(
                v.taskId(), v.title(), v.content(), v.deliverable(), v.acceptance(),
                v.priority(), v.isContract(), v.requiredSkills(), v.constraints(),
                SubTaskStatus.PENDING, v.context(), uncertainties,
                v.acceptanceKind(), null, v.draftCode(), refs);
    }

    private List<SubTaskView> finishConfirm(TaskView task, List<SubTaskView> confirmed) {
        if (taskService.updateStatus(task.id(), TaskStatus.IN_PROGRESS) == null) {
            throw new BizException("任务状态推进失败（updateStatus 未命中），请重试: taskId=" + task.id());
        }
        // 草案已全部转正、任务已离开 PLANNING ⇒ findings 描述的草案状态已不存在，必须清。
        // 不清的后果：后续读侧按 findings 非空判定「仍有未解阻断」，把已确认的任务标成待修，
        // 而它此刻已经 IN_PROGRESS、草案也已 PENDING（真机确认路径复现过）。
        planGateRunner.clear(confirmed);

        // `TD-16d-4` 读侧换源前置：暂存区行标 CONFIRMED + 软删，草案页从暂存区读后不再把
        // 已转正草案当待确认展示（设计 §3.1 第四态：deleted 让位置、CONFIRMED 记语义，缺一不可）。
        // best-effort：失败只告警不阻断确认主链路——草案已转正是既成事实，读侧短暂多显示
        // 已确认草案可通过重新进入页面恢复（确认标记是幂等 UPDATE，不产生脏数据）。
        try {
            planDraftRoundService.confirmRound(task.id());
        } catch (Exception e) {
            log.warn("暂存区确认标记失败（不阻断草案确认，读侧可能短暂多显示已确认草案）: taskId={}, err={}",
                    task.id(), e.getMessage());
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
            taskRunningSpecService.initialize(task.id(), baseline);
        } catch (Exception e) {
            log.warn("TaskRunningSpec Baseline 初始化失败（不阻断草案确认）: taskId={}, err={}",
                    task.id(), e.getMessage());
        }

        Long taskId = task.id();
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_CONFIRMED.code(),
                AgentRole.PLANNER, null, Map.of("subTaskCount", confirmed.size()));

        // 事务外触发自动分发（分发链内部有独立事务与事件），单条失败不阻断其余。
        // 确认草案是用户显式启动内循环的动作，不受 auto-assign-on-create
        // （任务创建即分发）开关控制；否则开关关闭时只能等孤儿扫描兜底，
        // 内循环无法自动运转。ready 守卫会自动拦住依赖未就绪的节点。
        // ADR-008：派生草案物化为 AWAITING_DERIVED / DONE，不进入分发链，按最新状态跳过。
        for (SubTaskView draft : confirmed) {
            SubTaskView current = subTaskService.getView(draft.id());
            if (current == null || current.status() != SubTaskStatus.PENDING) {
                continue;
            }
            try {
                subTaskDispatchService.dispatchPendingSubTaskAuto(draft.id(), AgentRole.EXECUTOR);
            } catch (Exception e) {
                log.warn("草案转正后自动分发失败（保持 PENDING 等待兜底任务）: subTaskId={}, err={}",
                        draft.id(), e.getMessage());
            }
        }
        log.info("任务规划草案已确认: taskId={}, subTaskCount={}", taskId, confirmed.size());
        return confirmed.stream().map(d -> subTaskService.getView(d.id())).toList();
    }

    /**
     * 幂等恢复：查找已确认（context 含 planConfirmedAt）的子任务。
     * <p>用于处理前次 confirmPlan 的 SubTaskView 状态变更已提交但 Task 状态更新未生效的场景。
     * ADR-008 起，派生草案物化后的状态是 AWAITING_DERIVED / DONE（不是 PENDING），
     * 因此三者都要纳入，否则半成功后的恢复会把「已物化的派生草案」当成不存在。</p>
     */
    private List<SubTaskView> recoverAlreadyConfirmed(Long taskId) {
        List<SubTaskView> alreadyConfirmed = new ArrayList<>();
        for (SubTaskStatus status : List.of(SubTaskStatus.PENDING,
                SubTaskStatus.AWAITING_DERIVED, SubTaskStatus.DONE)) {
            for (SubTaskView st : subTaskService.listViewsByTaskIdAndStatus(taskId, status)) {
                if (st.context() != null && st.context().containsKey("planConfirmedAt")) {
                    alreadyConfirmed.add(st);
                }
            }
        }
        return alreadyConfirmed;
    }

    /**
     * 拒绝草案：**先作废暂存区整轮**（{@code DISCARDED} + 软删），再把 Task 回退
     * {@code PENDING}，使该任务可重新拆解。
     *
     * <p><b>为什么必须清暂存区</b>：`rejectPlan` 原来只动执行表。真机验证
     * （23:13）reject 后重拆时，第一轮的 9 条 `ACTIVE` 行仍在，第二轮产出的
     * code 与之重叠 ⇒ 顶在唯一索引上 ⇒ `DuplicateKeyException` 被上层当成「幂等命中」吞掉
     * ⇒ 任务永久卡在 `PLANNING`，且用户看不到任何错误。</p>
     *
     * <p><b>为什么不把这整段圈进一个事务</b>：`planGateRunner.clear(...)` 内部走
     * `REQUIRES_NEW`（`TD-14` 决策 8 的刻意设计）。外层事务持锁 + 内层要新连接 ⇒
     * 内层等外层的锁、外层等内层返回，**连接级死锁**。2026-11-08 23:33 真机试过：
     * 请求 90s 不返回，`pg_stat_activity` 里 5 个会话互锁（1 个
     * `idle in transaction` 263s + 4 个等锁）。</p>
     *
     * <p><b>改用顺序保证安全</b>：把「作废暂存区」放在**第一步**。这样即使后续步骤中途
     * 失败，最坏结果只是「暂存区已清 + 部分草案仍待审」——下一轮拆解照样能跑通（这正是
     * `D4` 要达成的目标），而不是反过来留下「草案没了但暂存区还在」这种必然撞索引的状态。</p>
     *
     * @return 被取消的草案数量
     */
    @Override
    public int rejectPlan(Long taskId) {
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException(400, "任务不存在: " + taskId);
        }
        if (task.status() != TaskStatus.AWAITING_PLAN_CONFIRM) {
            throw new BizException(400, (task.status() == TaskStatus.PLANNING
                    ? "拆解仍在进行中，请等待草案产出后再拒绝"
                    : "当前状态不可拒绝草案")
                    + ": taskId=" + taskId + ", status=" + task.status());
        }
        List<PlanDraftView> staged = planDraftService.listByTask(taskId);

        // 第一步：作废暂存区整轮（`DISCARDED` + `deleted=1`）。放最前是因为它决定
        // 「下一轮能不能拆」——这是本条最要紧的副作用，不能挂在后续步骤成功之后。
        int discarded = planDraftRoundService.discardRound(taskId);

        // ADR-008：草案被整批作废 ⇒ 由它们派生出去的任务失去需求依据，一并取消
        // （派生 Task 是独立任务，不取消会继续跑并占用执行资源）。派生已完成的自然不在列表内。
        // 同时关闭该派生任务的需求会话：来源草案已作废、产出任务已取消，会话若仍停 FINALIZED，
        // 「查看任务」会指向已取消的任务、「重新生成」又必然 400（来源草案不在暂存区）。
        List<Long> cancelledDerivedTaskIds = new ArrayList<>();
        int closedConversationCount = 0;
        for (TaskView derived : taskDerivationService.listActivePlanDerivations(taskId)) {
            try {
                taskService.updateStatus(derived.id(), TaskStatus.CANCELLED);
                cancelledDerivedTaskIds.add(derived.id());
                if (conversationService.abandonByDerivedTask(derived.id())) {
                    closedConversationCount++;
                }
            } catch (Exception e) {
                log.warn("草案派生任务取消失败（需人工处理）: parentTaskId={}, derivedTaskId={}, err={}",
                        taskId, derived.id(), e.getMessage());
            }
        }

        taskService.updateStatus(taskId, TaskStatus.PENDING);
        int cancelledCount = staged.size();
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_PLAN_REJECTED.code(),
                AgentRole.PLANNER, null,
                Map.of("cancelledCount", cancelledCount, "discardedDraftRows", discarded,
                        "stagedDraftRows", staged.size(),
                        "cancelledDerivedTaskIds", cancelledDerivedTaskIds,
                        "closedDerivationConversationCount", closedConversationCount));
        log.info("任务规划草案已拒绝: taskId={}, cancelledCount={}, discardedDraftRows={}, "
                        + "cancelledDerivedTaskIds={}, closedDerivationConversations={}",
                taskId, cancelledCount, discarded, cancelledDerivedTaskIds, closedConversationCount);
        return cancelledCount;
    }

    // ══════════════════════════════════════════════════════════════
    //  `ADR-008` 草案期派生 / `TD-9` 单条草案 regenerate
    // ══════════════════════════════════════════════════════════════

    /**
     * {@inheritDoc}
     *
     * <p><b>为什么本方法不加事务</b>：{@code createPlanDerivation} 自带独立事务，提交后
     * 才能调 {@code decompose(Y)}——异步拆解线程要读到已提交的 `Y`；把两步圈进同一事务
     * 会让异步段读到「任务不存在」。</p>
     */
    @Override
    public TaskView deriveDraftByCode(Long taskId, String code) {
        PlanDraftView staged = planDraftService.getByCode(taskId, code);
        if (staged == null) {
            throw new BizException(400, "草案定位失败（可能已确认/已作废/非本任务）: taskId="
                    + taskId + ", code=" + code);
        }
        return deriveFrom(taskId, PlanDraftViewMapper.toSubTaskViews(List.of(staged)).get(0), null);
    }

    /**
     * 派生公共段：任务态 / 位置身份 / 深度闸门 / 幂等创建 / 时间线。
     *
     * @param timelineSubTaskId 执行表副本 id；暂存区草案没有副本时传 {@code null}
     *                          （时间线按任务聚合，{@code draftCode} 记在 payload 里）
     */
    private TaskView deriveFrom(Long taskId, SubTaskView draft, Long timelineSubTaskId) {
        TaskView task = taskService.getView(taskId);
        if (task == null) {
            throw new BizException(400, "草案所属任务不存在: " + taskId);
        }
        if (task.status() != TaskStatus.AWAITING_PLAN_CONFIRM) {
            throw new BizException(400, "只有草案待确认状态可派生: taskId=" + taskId
                    + ", status=" + task.status());
        }
        String draftCode = draft.draftCode();
        if (draftCode == null || draftCode.isBlank()) {
            // 派生以 code 为父锚；存量草案（V147 前）没有位置身份，先重拆一次即可获得
            throw new BizException(400, "该草案缺少位置编号（确认前历史草案），请先重新拆解后再派生: draftId="
                    + draft.id());
        }
        assertDerivationDepthAllowed(task, draft);

        // 幂等：同一条草案已有活跃派生时返回它；若它仍停在 PENDING，补触发拆解
        TaskView derived = taskDerivationService.findActivePlanDerivation(taskId, draftCode);
        boolean created = false;
        if (derived == null) {
            derived = taskDerivationService.createPlanDerivation(taskId, draftCode, draft);
            created = true;
        }
        if (derived.status() == TaskStatus.PENDING) {
            try {
                decompose(derived.id());
            } catch (BizException e) {
                throw new BizException(400, "派生任务已创建（id=" + derived.id() + "），但拆解未能启动（"
                        + e.getMessage() + "）；可在任务页重新触发拆解");
            }
        }
        if (created) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("draftCode", draftCode);
            payload.put("derivedTaskId", derived.id());
            payload.put("depth", derived.depth());
            payload.put("taskArchetype", task.taskArchetype() != null
                    ? task.taskArchetype().name() : TaskArchetype.DEFAULT().name());
            taskTimelineService.recordEvent(taskId, timelineSubTaskId,
                    TimelineEventCode.TASK_PLAN_DRAFT_DERIVED.code(),
                    AgentRole.PLANNER, null, payload);
        }
        log.info("草案已派生: taskId={}, code={}, derivedTaskId={}, created={}",
                taskId, draftCode, derived.id(), created);
        return derived;
    }

    /**
     * 派生深度闸门（`ADR-008` §19.7）：全局 `depth ≤ 2`，分档再收严（DECIDE 只允许 1 层）。
     *
     * <p>取 {@code min(分档上限, 全局上限)}：`AD-6` 起 `application.yml` 的 BUILD 档已降为 2，
     * 与 DB 的 `chk_task_depth`（≤2）一致；保留 min 是防御未来分档被调宽时不先用应用校验放行、
     * 再被 DB 以 500 拦下。</p>
     */
    private void assertDerivationDepthAllowed(TaskView task, SubTaskView draft) {
        TaskArchetype archetype = task.taskArchetype() != null
                ? task.taskArchetype() : TaskArchetype.DEFAULT();
        int configured = plannerDecomposeProperties.getMaxExpandDepth().limitOf(archetype);
        int limit = Math.min(configured, 2);
        int currentDepth = task.depth() != null ? task.depth() : 0;
        if (currentDepth + 1 > limit) {
            throw new BizException(400, String.format(
                    "该任务已派生 %d 层（%s 上限 %d 层）——继续细拆说明需求边界不清，请先收敛需求范围再派生: subTaskId=%d",
                    currentDepth, archetype.name(), limit, draft.id()));
        }
    }

    /**
     * 按 code 修订草案（暂存区唯一事实源；`AD-5a-5` 起不再有执行表副本）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDraftByCode(Long taskId, String code, List<String> requiredSkills, String constraints,
                                  List<Uncertainty> uncertainties, String acceptanceKind) {
        PlanDraftView staged = planDraftService.getByCode(taskId, code);
        if (staged == null) {
            throw new BizException(400, "草案定位失败（可能已确认/已作废/非本任务）: taskId="
                    + taskId + ", code=" + code);
        }
        planDraftService.updateByCode(taskId, code, null, null, null, requiredSkills, constraints,
                toUncertaintyDrafts(uncertainties), acceptanceKind);
        log.info("草案修订（暂存区）: taskId={}, code={}", taskId, code);
    }

    @Override
    public SubTaskView regenerateDraftByCode(Long taskId, String code) {
        PlanDraftView staged = planDraftService.getByCode(taskId, code);
        if (staged == null) {
            throw new BizException(400, "草案定位失败（可能已确认/已作废/非本任务）: taskId="
                    + taskId + ", code=" + code);
        }
        return plannerDecomposeAsyncService.regenerateStaged(taskId, code);
    }

    /** {@code Uncertainty}（JSONB 元素实体）→ 暂存区写入物料。 */
    private static List<UncertaintyDraft> toUncertaintyDrafts(List<Uncertainty> uncertainties) {
        if (uncertainties == null) {
            return null;
        }
        List<UncertaintyDraft> drafts = new ArrayList<>(uncertainties.size());
        for (Uncertainty u : uncertainties) {
            if (u != null) {
                drafts.add(new UncertaintyDraft(u.getKind(), u.getNote(), u.getRef(), u.getBlockedBy()));
            }
        }
        return drafts;
    }

    // ══════════════════════════════════════════════════════════════
    //  内部实现
    // ══════════════════════════════════════════════════════════════

    /** 构建 Baseline goal：任务标题 + 描述。 */
    private String buildBaselineGoal(TaskView task) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题: ").append(task.title());
        if (task.description() != null && !task.description().isBlank()) {
            sb.append("\n任务描述: ").append(task.description());
        }
        return sb.toString();
    }

    /** 构建 Baseline raw：子任务 DAG 结构摘要。 */
    private String buildBaselineRaw(List<SubTaskView> drafts) {
        StringBuilder sb = new StringBuilder();
        sb.append("子任务 DAG 结构（共 ").append(drafts.size()).append(" 个）：\n");
        for (int i = 0; i < drafts.size(); i++) {
            SubTaskView d = drafts.get(i);
            sb.append(i + 1).append(". ").append(d.title());
            if (d.dependsOn() != null && !d.dependsOn().isEmpty()) {
                sb.append(" [依赖: ").append(d.dependsOn().size()).append("个前置]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从草案 context 中提取 Planner Agent ID。
     *
     * <p><b>为什么逐条扫而不是只看首条</b>：本方法的两个消费方（语义门的「借谁家模型」、
     * Baseline 的 `createdBy`）都不关心是哪一条草案带的 id。只看首条的话，一旦首条是展开出来的
     * 子草案（`attemptId=null`，context 可能不带 `plannerAgentId`），整批就退化成「无 planner」——
     * 语义门会以「未取到 planner agent」永久 fail-open，静默失效。</p>
     */
    private Long extractPlannerAgentId(List<SubTaskView> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return null;
        }
        for (SubTaskView d : drafts) {
            Map<String, Object> ctx = d == null ? null : d.context();
            if (ctx != null && ctx.get("plannerAgentId") instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }
}
