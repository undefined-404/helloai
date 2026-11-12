package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.util.ExecutionEnv;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskDraft;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskStateMachineService;
import com.helloai.core.task.service.TaskDerivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派生 Task 写原语实现（`ADR-008`）。
 *
 * <p>持有 {@code TaskMapper} + {@code SubTaskMapper}（同域）+ 状态机契约：
 * 执行期派生需要在<b>同一事务</b>里「建 `Y` + 把父 SubTask 置 `AWAITING_DERIVED`」，
 * 不能经 {@code SubTaskService}（它反向依赖本服务）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDerivationServiceImpl implements TaskDerivationService {

    /** `ADR-008` §19.7：全局层级上限（根 0 + 最多两级派生）。 */
    private static final int GLOBAL_MAX_DEPTH = 2;

    private static final String REASON_PLAN = "PLAN";
    private static final String REASON_RESTRUCTURE = "RESTRUCTURE";

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;
    private final SubTaskStateMachineService subTaskStateMachineService;

    @Override
    public TaskView findActivePlanDerivation(Long parentTaskId, String parentDraftCode) {
        if (parentTaskId == null || parentDraftCode == null || parentDraftCode.isBlank()) {
            return null;
        }
        Task derived = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getParentTaskId, parentTaskId)
                .eq(Task::getParentDraftCode, parentDraftCode)
                .ne(Task::getStatus, TaskStatus.CANCELLED)
                .orderByDesc(Task::getId)
                .last("LIMIT 1"));
        return derived == null ? null : TaskServiceImpl.toView(derived);
    }

    @Override
    public List<TaskView> listActivePlanDerivations(Long parentTaskId) {
        if (parentTaskId == null) {
            return List.of();
        }
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getParentTaskId, parentTaskId)
                        .eq(Task::getDerivationReason, REASON_PLAN)
                        .notIn(Task::getStatus, TaskStatus.DONE, TaskStatus.CANCELLED)
                        .orderByAsc(Task::getId))
                .stream().map(TaskServiceImpl::toView).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskView createPlanDerivation(Long parentTaskId, String parentDraftCode, SubTaskView draft) {
        if (parentTaskId == null || parentDraftCode == null || parentDraftCode.isBlank() || draft == null) {
            throw new BizException(400, "草案派生参数不完整: parentTaskId=" + parentTaskId
                    + ", parentDraftCode=" + parentDraftCode);
        }
        Task parent = taskMapper.selectById(parentTaskId);
        if (parent == null) {
            throw new BizException(400, "草案所属任务不存在: " + parentTaskId);
        }
        int parentDepth = parent.getDepth() != null ? parent.getDepth() : 0;
        int derivedDepth = parentDepth + 1;
        if (derivedDepth > GLOBAL_MAX_DEPTH) {
            // DB 的 chk_task_depth 会以 500 形态拦下；这里先给可纠正的 400 与提示文案
            throw new BizException(400, "派生层级已达上限（" + GLOBAL_MAX_DEPTH
                    + " 层）：继续细拆说明需求边界不清，请先收敛需求范围");
        }

        Task derived = new Task();
        derived.setTitle(draft.title());
        derived.setDescription(buildDerivedDescription(draft));
        derived.setStatus(TaskStatus.PENDING);
        derived.setOwnerId(parent.getOwnerId());
        derived.setSlaMinutes(parent.getSlaMinutes());
        derived.setPriority(draft.priority() != null ? draft.priority() : parent.getPriority());
        derived.setAgentPolicy(parent.getAgentPolicy());
        List<String> draftSkills = draft.requiredSkills();
        derived.setRequiredSkills(draftSkills != null && !draftSkills.isEmpty()
                ? draftSkills : parent.getRequiredSkills());
        // `ADR-008` §19.7：archetype 继承父任务，不重新判定（只严不宽由继承值天然满足）
        derived.setTaskArchetype(parent.getTaskArchetype());
        derived.setParentTaskId(parentTaskId);
        derived.setParentDraftCode(parentDraftCode);
        derived.setRootTaskId(parent.getRootTaskId() != null ? parent.getRootTaskId() : parent.getId());
        derived.setDepth(derivedDepth);
        derived.setDerivationReason(REASON_PLAN);
        derived.setDerivationRound(
                taskMapper.selectMaxPlanDerivationRound(parentTaskId, parentDraftCode) + 1);
        try {
            taskMapper.insert(derived);
        } catch (DuplicateKeyException e) {
            // 并发双击：uk_task_parent_draft_active 兜底。转成用户可纠正的 400，而不是 500
            throw new BizException(400, "该草案已有进行中的派生任务，请刷新后查看: parentTaskId="
                    + parentTaskId + ", code=" + parentDraftCode);
        }
        log.info("草案期派生任务已创建: parentTaskId={}, code={}, derivedTaskId={}, depth={}, round={}",
                parentTaskId, parentDraftCode, derived.getId(), derivedDepth, derived.getDerivationRound());
        return TaskServiceImpl.toView(derived);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindParentSubTask(Long derivedTaskId, Long parentSubTaskId) {
        if (derivedTaskId == null || parentSubTaskId == null) {
            return;
        }
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, derivedTaskId)
                .isNull(Task::getParentSubTaskId)
                .set(Task::getParentSubTaskId, parentSubTaskId)
                // 双锚互斥（chk_task_parent_anchor）：回填 SubTask 锚后草案 code 不再保留，
                // 血缘仍可由 parent_task_id + parent_sub_task_id 还原
                .set(Task::getParentDraftCode, null));
        if (updated > 0) {
            log.info("派生任务父锚已回填: derivedTaskId={}, parentSubTaskId={}",
                    derivedTaskId, parentSubTaskId);
            return;
        }
        // update 落空：可能已绑定（幂等）或已绑定到别的子任务（数据异常）
        Task current = taskMapper.selectById(derivedTaskId);
        if (current == null) {
            throw new BizException(400, "派生任务不存在，无法回填父锚: " + derivedTaskId);
        }
        if (!parentSubTaskId.equals(current.getParentSubTaskId())) {
            throw new BizException(400, "派生任务已绑定其他父 SubTask，拒绝覆盖: derivedTaskId="
                    + derivedTaskId + ", existing=" + current.getParentSubTaskId()
                    + ", expected=" + parentSubTaskId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskView createRestructureTask(TaskDraft draft, Long parentSubTaskId) {
        if (draft == null || parentSubTaskId == null) {
            throw new BizException(400, "执行期派生参数不完整: parentSubTaskId=" + parentSubTaskId);
        }
        SubTask parentSub = subTaskMapper.selectById(parentSubTaskId);
        if (parentSub == null) {
            throw new BizException(400, "父 SubTask 不存在: " + parentSubTaskId);
        }
        // 状态判据收敛到 assertDerivable（RESTRUCTURING 直接放行）。AWAITING_DERIVED 不能
        // 一律拒——它只是派生结果的投影，派生任务被物理删除后投影已失实，须按
        // 「是否还有活跃派生」这个事实判定，否则用户永久卡在无出口的等待派生态。
        assertDerivable(parentSub);
        Task parentTask = taskMapper.selectById(parentSub.getTaskId());
        if (parentTask == null) {
            throw new BizException(400, "父任务不存在: " + parentSub.getTaskId());
        }
        int parentDepth = parentTask.getDepth() != null ? parentTask.getDepth() : 0;
        int derivedDepth = parentDepth + 1;
        if (derivedDepth > GLOBAL_MAX_DEPTH) {
            throw new BizException(400, "派生层级已达上限（" + GLOBAL_MAX_DEPTH
                    + " 层）：继续细拆说明需求边界不清，请先收敛需求范围");
        }
        Long existingDerivations = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .eq(Task::getParentSubTaskId, parentSubTaskId));
        int round = (existingDerivations == null ? 0 : existingDerivations.intValue()) + 1;

        Task derived = new Task();
        derived.setTitle(draft.title());
        derived.setDescription(draft.description());
        derived.setStatus(draft.status() != null ? draft.status() : TaskStatus.PENDING);
        derived.setContext(inheritExecutionEnv(draft.context(), parentTask.getContext()));
        derived.setOwnerId(draft.ownerId() != null ? draft.ownerId() : parentTask.getOwnerId());
        // 继承父任务的执行口径（archetype 只严不宽；SLA / 优先级 / 策略沿用，避免派生任务跑偏）
        derived.setSlaMinutes(parentTask.getSlaMinutes());
        derived.setPriority(parentTask.getPriority());
        derived.setAgentPolicy(parentTask.getAgentPolicy());
        List<String> draftSkills = draft.requiredSkills();
        derived.setRequiredSkills(draftSkills != null && !draftSkills.isEmpty()
                ? draftSkills : parentTask.getRequiredSkills());
        derived.setTaskArchetype(parentTask.getTaskArchetype());
        derived.setParentSubTaskId(parentSubTaskId);
        derived.setRootTaskId(parentTask.getRootTaskId() != null ? parentTask.getRootTaskId() : parentTask.getId());
        derived.setDepth(derivedDepth);
        derived.setDerivationReason(REASON_RESTRUCTURE);
        derived.setDerivationRound(round);
        try {
            taskMapper.insert(derived);
        } catch (DuplicateKeyException e) {
            // 并发双击：uk_task_parent_sub_task_active 兜底，转成用户可纠正的 400
            throw new BizException(400, "该子任务已有进行中的派生任务，请刷新后查看: parentSubTaskId="
                    + parentSubTaskId);
        }
        // 建 Y 与父状态迁移同事务：避免「Y 已建但父仍停在重构中」的半状态（对账也看不见）
        subTaskStateMachineService.changeStatus(parentSubTaskId, SubTaskStatus.AWAITING_DERIVED, null,
                Map.of("derivedTaskId", derived.getId(), "derivationReason", REASON_RESTRUCTURE));
        log.info("执行期派生任务已创建: parentSubTaskId={}, derivedTaskId={}, depth={}, round={}",
                parentSubTaskId, derived.getId(), derivedDepth, round);
        return TaskServiceImpl.toView(derived);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskView createDraftDeriveTask(TaskDraft draft, Long parentTaskId, String parentDraftCode) {
        if (draft == null || parentTaskId == null
                || parentDraftCode == null || parentDraftCode.isBlank()) {
            throw new BizException(400, "草案期派生参数不完整: parentTaskId=" + parentTaskId
                    + ", parentDraftCode=" + parentDraftCode);
        }
        Task parent = taskMapper.selectById(parentTaskId);
        if (parent == null) {
            throw new BizException(400, "草案所属任务不存在: " + parentTaskId);
        }
        int parentDepth = parent.getDepth() != null ? parent.getDepth() : 0;
        int derivedDepth = parentDepth + 1;
        if (derivedDepth > GLOBAL_MAX_DEPTH) {
            // 与 createPlanDerivation / createRestructureTask 同口径：DB 的 chk_task_depth
            // 会以 500 形态拦下，先给可纠正的 400
            throw new BizException(400, "派生层级已达上限（" + GLOBAL_MAX_DEPTH
                    + " 层）：继续细拆说明需求边界不清，请先收敛需求范围");
        }

        Task derived = new Task();
        derived.setTitle(draft.title());
        derived.setDescription(draft.description());
        derived.setStatus(draft.status() != null ? draft.status() : TaskStatus.PENDING);
        derived.setContext(inheritExecutionEnv(draft.context(), parent.getContext()));
        derived.setOwnerId(draft.ownerId() != null ? draft.ownerId() : parent.getOwnerId());
        // 执行口径沿用父任务（同 createRestructureTask）：派生任务跑在同一台机器、同一套
        // SLA / 优先级 / 策略下，只严不宽
        derived.setSlaMinutes(parent.getSlaMinutes());
        derived.setPriority(parent.getPriority());
        derived.setAgentPolicy(parent.getAgentPolicy());
        List<String> draftSkills = draft.requiredSkills();
        derived.setRequiredSkills(draftSkills != null && !draftSkills.isEmpty()
                ? draftSkills : parent.getRequiredSkills());
        derived.setTaskArchetype(parent.getTaskArchetype());
        // 草案期父锚 = (parent_task_id, parent_draft_code)，与 chk_task_parent_anchor 一致
        derived.setParentTaskId(parentTaskId);
        derived.setParentDraftCode(parentDraftCode);
        derived.setRootTaskId(parent.getRootTaskId() != null ? parent.getRootTaskId() : parent.getId());
        derived.setDepth(derivedDepth);
        derived.setDerivationReason(REASON_PLAN);
        derived.setDerivationRound(
                taskMapper.selectMaxPlanDerivationRound(parentTaskId, parentDraftCode) + 1);
        try {
            taskMapper.insert(derived);
        } catch (DuplicateKeyException e) {
            // uk_task_parent_draft_active 兜底：对话双击 / 并发定稿
            throw new BizException(400, "该草案已有进行中的派生任务，请刷新后查看: parentTaskId="
                    + parentTaskId + ", code=" + parentDraftCode);
        }
        log.info("草案期派生任务已创建（对话定稿）: parentTaskId={}, code={}, derivedTaskId={}, depth={}",
                parentTaskId, parentDraftCode, derived.getId(), derivedDepth);
        return TaskServiceImpl.toView(derived);
    }

    @Override
    public boolean restructureViable(Long parentSubTaskId) {        // 只判「主体是否还在」：状态不对（AWAITING_DERIVED / BLOCKED 等）是可纠正的（转回
        // RESTRUCTURING 即可），必须让用户看到原因，不能静默降级——否则用户以为派生链还在、
        // 实际已是独立任务，血缘悄然断开。状态 / 层级校验交给 createRestructureTask 的 400。
        if (parentSubTaskId == null) {
            return false;
        }
        return subTaskMapper.selectById(parentSubTaskId) != null;
    }

    @Override
    public TaskView findActiveRestructureDerivation(Long parentSubTaskId) {
        if (parentSubTaskId == null) {
            return null;
        }
        // 条件与 uk_task_parent_sub_task_active 的谓词保持一致：同一个读法，避免索引判活
        // 与代码判活出现两个口径（deleted 由 @TableLogic 自动追加）。
        Task derived = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getParentSubTaskId, parentSubTaskId)
                .ne(Task::getStatus, TaskStatus.CANCELLED)
                .orderByDesc(Task::getId)
                .last("LIMIT 1"));
        return derived == null ? null : TaskServiceImpl.toView(derived);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertDerivable(Long parentSubTaskId) {
        if (parentSubTaskId == null) {
            throw new BizException(400, "子任务 id 不能为空");
        }
        SubTask parentSub = subTaskMapper.selectById(parentSubTaskId);
        if (parentSub == null) {
            throw new BizException(400, "父 SubTask 不存在: " + parentSubTaskId);
        }
        assertDerivable(parentSub);
    }

    /**
     * 判据本体（已持有父行时复用，避免入口再查一次）。
     *
     * <p>{@code AWAITING_DERIVED} 必须核对事实才能下结论：它是派生结果的投影，
     * 派生任务被物理删除后投影不会自动回退（{@code deleteTaskCascade} 不写父状态，
     * 对账② 又扫不到 {@code deleted = 1} 的行），只看状态会把「已删」误判成「已完成」，
     * 把用户永久堵在 AWAITING_DERIVED 里——UI 该状态连「拆出去」入口都没有。</p>
     */
    private void assertDerivable(SubTask parentSub) {
        SubTaskStatus status = parentSub.getStatus();
        if (status == SubTaskStatus.RESTRUCTURING) {
            return;
        }
        if (status != SubTaskStatus.AWAITING_DERIVED) {
            throw new BizException(400, "仅重构中状态可派生独立任务，当前状态: " + status);
        }
        TaskView alive = findActiveRestructureDerivation(parentSub.getId());
        if (alive != null) {
            throw new BizException(400, "该子任务已完成二次拆分（派生任务 #" + alive.id()
                    + "），如需重新拆分请先删除或作废该派生任务");
        }
        // 事实无活跃派生 ⇒ 标记已失实，退回人工接管态让用户重新拆。
        subTaskStateMachineService.changeStatus(parentSub.getId(), SubTaskStatus.RESTRUCTURING, null,
                Map.of("derivationSelfHeal", "ACTIVE_DERIVATION_MISSING"));
        log.warn("派生标记自愈：子任务 {} 停在 AWAITING_DERIVED 但无有效派生任务（已删或已作废），"
                + "已退回 RESTRUCTURING 允许重新拆分", parentSub.getId());
    }

    /**
     * 派生任务 Y 继承父任务 X 的执行环境声明。
     *
     * <p><b>为什么必须继承</b>：Y 是 X 的细分，跑在同一台机器、同一个仓库、同样的数据源上——
     * 环境不随任务行分裂。不继承的话 Y 的 {@code context.executionEnv} 是空的，拆解时提示词
     * 只能渲染「未声明」，模型又回到「仓库在哪」的猜测状态（`2026-11-12 09:35` 那次
     * 「缺仓库检出 / schema / DB 连接」人工介入的成因）。</p>
     *
     * <p><b>只搬这一个键</b>：父任务 context 里还有 requirementPackage / runningSpec / 拆解快照，
     * 整份复制会让 Y 继承一份与本需求无关的旧包。{@code draft.context} 里显式给的值优先
     * （{@code putIfAbsent}）——将来有 UI 写入时，允许单个派生任务覆盖。</p>
     */
    private static Map<String, Object> inheritExecutionEnv(Map<String, Object> draftContext,
                                                           Map<String, Object> parentContext) {
        if (parentContext == null) {
            return draftContext;
        }
        Object inherited = parentContext.get(ExecutionEnv.CONTEXT_KEY);
        if (inherited == null) {
            return draftContext;
        }
        Map<String, Object> merged = draftContext == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(draftContext);
        merged.putIfAbsent(ExecutionEnv.CONTEXT_KEY, inherited);
        return merged;
    }

    /**
     * 派生 Task 的描述由草案四要素装配：派生任务会独立走一次拆解，
     * 需求边界（内容）与交付/验收口径不能只留在父任务的草案行里。
     */
    private static String buildDerivedDescription(SubTaskView draft) {
        StringBuilder sb = new StringBuilder();
        if (draft.content() != null && !draft.content().isBlank()) {
            sb.append(draft.content().trim());
        }
        appendSection(sb, "交付物", draft.deliverable());
        appendSection(sb, "验收判据", draft.acceptance());
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append(label).append("：").append(value.trim());
    }
}
