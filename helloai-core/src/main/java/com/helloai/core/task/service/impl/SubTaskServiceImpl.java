package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskClaimConstraint;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.SubTaskDraft;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.projection.SubTaskProjection;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.SubTaskLeaseService;
import com.helloai.core.task.service.SubTaskNotificationService;
import com.helloai.core.task.service.SubTaskQueryService;
import com.helloai.core.task.service.SubTaskStateMachineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务领域服务<b>薄门面</b>（RM9-b：原 1592 行上帝类 → 门面 + 4 职责类）。
 *
 * <p><b>本类保留类名与 {@code implements SubTaskService} 不变</b> ⇒ 外部调用方与 Spring 注入点
 * <b>零改动</b>，本笔可<b>单笔 revert</b>。类内只剩两类内容：</p>
 * <ol>
 *     <li><b>领域写入原语</b>（{@code updateDraft} / {@code create} / {@code updateDependsOn} /
 *         {@code createBatch} / {@code updateContext} / {@code updateDeadline} / {@code saveDrafts}
 *         / {@code unlinkByAssignedAgent} / {@code physicalDeleteByTaskId}）—— 均为
 *         「单实体单次原子写 + 无状态迁移语义」，属 §4.3.2 门面行所定的「MyBatis-Plus 基类能力 +
 *         草稿/技能等配置面（低频写）」；</li>
 *     <li><b>逐方法委托</b>给 4 个职责类（零业务分支）。</li>
 * </ol>
 *
 * <p><b>拆分后的类结构（4 职责类，方向为有向无环图）</b>：</p>
 * <pre>
 * SubTaskServiceImpl（门面：CRUD 原语 + 委托）
 *   ├── SubTaskQueryService       只读查询与视图投影（叶子）
 *   ├── SubTaskNotificationService 出站副作用（inbox / 摘要 / 领域事件） → Query
 *   ├── SubTaskStateMachineService 状态迁移唯一入口（含 claimAtomic / 租约回收状态半段） → Notification
 *   └── SubTaskLeaseService        租约 / 回收 / 巡检 → StateMachine（仅回收状态半段）
 * </pre>
 *
 * <p><b>对 2026-08-23「§7.8 选项二：书面声明不继续拆分」的正面回应</b>（历史声明<b>保留</b>如下，
 * 其顾虑已被逐条消解，而非被无视）：</p>
 * <ul>
 *     <li>已剥离：状态机校验（{@code SubTaskStateMachine}）、核验编排（{@code SubTaskReviewServiceImpl}）、
 *         执行编排（{@code SubTaskExecutionServiceImpl}）、评分计算（{@code ImplicitScoreCalculator}）、
 *         结果回写（{@code ExecutionResultHandler}）；</li>
 *     <li>剩余职责：子任务 CRUD / 依赖 / 状态流转（{@code changeStatus} 及
 *         claim/start/submit/complete/pause/resume/block/rework 收敛入口）+ 事件发布
 *         （outbox / 收件箱 / 交接）+ 评分联动 + 心跳 / 并发配额 + 跨域统计与级联删除收口；</li>
 *     <li>不拆理由：状态流转各入口共享同一状态机与事件发布链，拆分会造成事件序与事务边界在类间漂移；
 *         已外置的均为无状态工具或独立引擎，剩余部分无独立可测职责可继续剥离。</li>
 * </ul>
 * <p><b>顾虑①「事件序与事务边界在类间漂移」</b>：本笔把<b>整条状态流转链原样整体搬迁</b>到
 * {@link SubTaskStateMachineService}（{@code changeStatus} 的
 * 「读现状 → 校验 → 写上下文/租约 → 落库 → outbox → 收件箱 → 交接 → 领域事件 → 心跳」顺序与事务边界
 * 一字未改）⇒ 事件序与事务边界<b>仍在同一个类内</b>，漂移风险未被引入；门面只在外部入口
 * 保留 {@code @Transactional} 以维持原事务开启位置。</p>
 * <p><b>顾虑②「剩余部分无独立可测职责」</b>：实测该判断不再成立 —— 被剥离的三簇<b>各自已有独立单测</b>
 * （{@code SubTaskServiceIsReadyTest} / {@code SubTaskServiceMergeSkillsTest} /
 * {@code SubTaskServiceLeaseReclaimTest} / {@code SubTaskServiceQuotaTest} /
 * {@code SubTaskServiceReworkBudgetTest} / {@code SubTaskServiceHandoverTest}），
 * 即它们本就具备「独立可测职责」，只是此前与状态机同处一文件。</p>
 * <p><b>顾虑③「按行数机械拆」</b>：本笔<b>不按方法数量</b>拆，按 §9.1 业务职责切分（只读查询 /
 * 出站副作用 / 租约调度 / 状态迁移），故不落 §9.2 所禁的
 * {@code FooHelper/FooManager} 互相转发形态（4 类互不转发、方向无环）。</p>
 *
 * <p><b>写入纪律（拆分未放松）</b>：{@code sub_task.status} 的全部迁移仍只经
 * {@link SubTaskStateMachineService}（§4.3.2 验收口径 ii）；本类自己的写操作<b>均不改 status</b>，
 * 唯一例外是 {@link #create(SubTask, Long)} 在<b>插入新行</b>时落初始态 {@code PENDING}（插入非迁移）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskServiceImpl extends ServiceImpl<SubTaskMapper, SubTask>
        implements SubTaskService {

    /** 同域 Mapper 直捅（CODE_STYLE §7.1：同域合规）；显式持有以便手动装配场景可测（见原类注释）。 */
    private final SubTaskMapper subTaskMapper;
    private final SubTaskQueryService queryService;
    private final SubTaskStateMachineService stateMachineService;
    private final SubTaskLeaseService leaseService;

    // ══════════════════════════════════════════════════════════════
    //  门面自有：领域写入原语（单实体原子写，无状态迁移语义）
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDraft(Long id, List<String> requiredSkills, String constraints, List<Uncertainty> uncertainties) {
        SubTask subTask = getById(id);
        if (subTask == null) {
            throw new BizException("草案不存在: " + id);
        }
        // fail-close：仅草案态可编辑；已转正子任务走返工/改派链路，不得绕过状态机直改
        if (subTask.getStatus() != SubTaskStatus.PENDING_PLAN_REVIEW) {
            throw new BizException("仅草案待审状态可编辑，当前状态: " + subTask.getStatus());
        }
        if (requiredSkills != null) {
            subTask.setRequiredSkills(requiredSkills);
        }
        if (constraints != null) {
            subTask.setConstraints(constraints);
        }
        if (uncertainties != null) {
            // G-011：人工编辑为权威输入不做 kind 强校验，但落库侧保持数据不变量——
            // 空白 note 条目丢弃（无信息量）、非法 kind 降级 UNCONFIRMED（D3 fail-close 同口径）
            List<Uncertainty> normalized = new ArrayList<>();
            for (Uncertainty u : uncertainties) {
                if (u == null || u.getNote() == null || u.getNote().isBlank()) {
                    continue;
                }
                if (!Uncertainty.KIND_ASSUMPTION.equals(u.getKind())
                        && !Uncertainty.KIND_UNCONFIRMED.equals(u.getKind())) {
                    u.setKind(Uncertainty.KIND_UNCONFIRMED);
                }
                normalized.add(u);
            }
            subTask.setUncertainties(normalized);
        }
        updateById(subTask);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTask create(SubTask subTask, Long assignedAgentId) {
        subTask.setAssignedAgentId(null);
        subTask.setStatus(SubTaskStatus.PENDING);
        save(subTask);
        if (assignedAgentId != null) {
            // 状态写入走唯一入口（PENDING → ASSIGNED），不在门面另开写路径
            stateMachineService.claim(subTask.getId(), assignedAgentId);
        }
        return getById(subTask.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SubTask> createBatch(List<BatchCreateItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<SubTask> created = new ArrayList<>(items.size());
        int failCount = 0;
        for (BatchCreateItem item : items) {
            if (item == null || item.getSubTask() == null) {
                failCount++;
                continue;
            }
            try {
                SubTask subTask = create(item.getSubTask(), item.getAssignedAgentId());
                created.add(subTask);
            } catch (Exception e) {
                failCount++;
                log.warn("子任务批量派发单项失败: title={}, agentId={}, err={}",
                        item.getSubTask().getTitle(), item.getAssignedAgentId(), e.getMessage());
            }
        }
        log.info("子任务批量派发: total={}, success={}, failed={}",
                items.size(), created.size(), failCount);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDependsOn(Long subTaskId, List<Long> dependsOnIds) {
        List<Long> ids = dependsOnIds != null ? dependsOnIds : Collections.emptyList();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(ids.get(i));
        }
        json.append(']');
        int updated = subTaskMapper.updateDependsOn(subTaskId, json.toString(), OffsetDateTime.now());
        if (updated == 0) {
            throw new BizException("子任务不存在或已删除，无法写入依赖: " + subTaskId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>读改写回写整体不透明</b>：取<b>最新行</b>而非消费方快照，既保留同行互斥
     * （{@code @Version} CAS 仍生效），又避免消费方携带的陈旧 {@code version} 让写入静默丢失。
     * 与 {@code SubTaskCommandPortAdapter#updateContext} 同源（适配器现委托本方法）。</p>
     */
    @Override
    public void updateContext(Long subTaskId, Map<String, Object> context) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            return;
        }
        subTask.setContext(context);
        updateById(subTask);
    }

    @Override
    public boolean updateDeadline(Long subTaskId, OffsetDateTime deadline) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            return false;
        }
        subTask.setDeadline(deadline);
        return updateById(subTask);
    }

    /**
     * {@inheritDoc}
     *
     * <p>RM9-b 归属说明：本方法含<b>批量插入</b>（{@code saveBatch}，MyBatis-Plus 基类能力），
     * 属「写」而非「只读投影」，故留在门面（沿用 §4.3.2 门面行「保留 {@code extends ServiceImpl}
     * 供 CRUD」的判据）；映射两半（{@code toEntity} / {@code toView}）统一在
     * {@link SubTaskProjection}，避免与只读簇各持一份口径（RM9-b 微调①）。</p>
     */
    @Override
    public List<SubTaskView> saveDrafts(List<SubTaskDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<SubTask> entities = drafts.stream().map(SubTaskProjection::toEntity).toList();
        saveBatch(entities);
        return entities.stream().map(SubTaskProjection::toView).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlinkByAssignedAgent(Long agentId) {
        subTaskMapper.update(null, new LambdaUpdateWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId)
                .set(SubTask::getAssignedAgentId, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int physicalDeleteByTaskId(Long taskId) {
        // 同域 Mapper 直捅（CODE_STYLE §7.1：同域合规）
        return subTaskMapper.physicalDeleteByTaskId(taskId);
    }

    // ══════════════════════════════════════════════════════════════
    //  委托：只读查询与视图投影 → SubTaskQueryService
    // ══════════════════════════════════════════════════════════════

    @Override
    public List<String> requiredSkillsOf(Long taskId) {
        return queryService.requiredSkillsOf(taskId);
    }

    @Override
    public List<String> mergeSkills(SubTask subTask) {
        return queryService.mergeSkills(subTask);
    }

    @Override
    public List<String> mergeSkills(Long subTaskId) {
        return queryService.mergeSkills(subTaskId);
    }

    @Override
    public SubTaskClaimConstraint claimConstraint(Long subTaskId) {
        return queryService.claimConstraint(subTaskId);
    }

    @Override
    public IPage<SubTask> list(Long taskId, SubTaskStatus status, Long assignedAgentId, Integer page, int pageSize) {
        return queryService.list(taskId, status, assignedAgentId, page, pageSize);
    }

    @Override
    public List<SubTask> listAvailable() {
        return queryService.listAvailable();
    }

    @Override
    public List<SubTask> listMine(Long assignedAgentId) {
        return queryService.listMine(assignedAgentId);
    }

    @Override
    public List<SubTask> listRecentlyChanged(OffsetDateTime since, int limit) {
        return queryService.listRecentlyChanged(since, limit);
    }

    @Override
    public SubTask getByIdForUpdate(Long subTaskId) {
        return queryService.getByIdForUpdate(subTaskId);
    }

    @Override
    public boolean isReady(SubTask subTask) {
        return queryService.isReady(subTask);
    }

    @Override
    public Map<String, Integer> countByStatusForAgent(Long agentId) {
        return queryService.countByStatusForAgent(agentId);
    }

    @Override
    public long countByAssignedAgent(Long agentId) {
        return queryService.countByAssignedAgent(agentId);
    }

    @Override
    public long countReviewByReviewerAgent(Long agentId) {
        return queryService.countReviewByReviewerAgent(agentId);
    }

    @Override
    public long countByTaskIdExcludingStatus(Long taskId, SubTaskStatus excluded) {
        return queryService.countByTaskIdExcludingStatus(taskId, excluded);
    }

    @Override
    public long countByTaskIdAndStatus(Long taskId, SubTaskStatus status) {
        return queryService.countByTaskIdAndStatus(taskId, status);
    }

    @Override
    public List<SubTask> selectInFlightByAgent(Long agentId, int limit) {
        return queryService.selectInFlightByAgent(agentId, limit);
    }

    @Override
    public int countInFlightByAgent(Long agentId) {
        return queryService.countInFlightByAgent(agentId);
    }

    @Override
    public int countInFlightAll() {
        return queryService.countInFlightAll();
    }

    @Override
    public boolean existsInFlightAssignedOrInProgress(Long agentId) {
        return queryService.existsInFlightAssignedOrInProgress(agentId);
    }

    @Override
    public List<SubTask> listInFlightAssignedOrInProgress(Long agentId) {
        return queryService.listInFlightAssignedOrInProgress(agentId);
    }

    @Override
    public List<Long> listPendingUnassignedWithoutActiveExecutionRecord(int limit) {
        return queryService.listPendingUnassignedWithoutActiveExecutionRecord(limit);
    }

    @Override
    public List<Long> listStalePendingWithoutExecutionRecord(OffsetDateTime cutoff, int limit) {
        return queryService.listStalePendingWithoutExecutionRecord(cutoff, limit);
    }

    @Override
    public List<Long> listStaleBlockedSubTaskIds(OffsetDateTime cutoff, int limit) {
        return queryService.listStaleBlockedSubTaskIds(cutoff, limit);
    }

    @Override
    public SubTaskView getView(Long subTaskId) {
        return queryService.getView(subTaskId);
    }

    @Override
    public List<SubTaskView> listViewsByTaskId(Long taskId) {
        return queryService.listViewsByTaskId(taskId);
    }

    @Override
    public List<SubTaskView> listViewsByTaskIdAndStatus(Long taskId, SubTaskStatus status) {
        return queryService.listViewsByTaskIdAndStatus(taskId, status);
    }

    @Override
    public List<SubTaskView> listDraftsInInsertOrder(Long taskId, SubTaskStatus status) {
        return queryService.listDraftsInInsertOrder(taskId, status);
    }

    @Override
    public List<SubTaskView> listViewsByIds(Collection<Long> ids) {
        return queryService.listViewsByIds(ids);
    }

    // ══════════════════════════════════════════════════════════════
    //  委托：状态迁移唯一入口 → SubTaskStateMachineService
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        stateMachineService.changeStatus(subTaskId, newStatus, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch) {
        stateMachineService.changeStatus(subTaskId, newStatus, agentId, contextPatch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long subTaskId, Long agentId) {
        stateMachineService.claim(subTaskId, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        stateMachineService.start(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        stateMachineService.submit(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long subTaskId) {
        stateMachineService.complete(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long subTaskId) {
        stateMachineService.pause(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(Long subTaskId) {
        stateMachineService.resume(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long subTaskId) {
        stateMachineService.cancel(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        stateMachineService.block(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId, String reason, Long reporterAgentId) {
        stateMachineService.block(subTaskId, reason, reporterAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rework(Long subTaskId, Long reworkAgentId) {
        return stateMachineService.rework(subTaskId, reworkAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reworkFresh(Long subTaskId, Long reworkAgentId) {
        stateMachineService.reworkFresh(subTaskId, reworkAgentId);
    }

    @Override
    public void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra) {
        stateMachineService.markManualIntervention(subTaskId, reason, extra);
    }

    @Override
    public void clearManualIntervention(Long subTaskId) {
        stateMachineService.clearManualIntervention(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignNext(Long agentId, Long subTaskId) {
        stateMachineService.assignNext(agentId, subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses) {
        return stateMachineService.resetToPendingForDispatch(subTaskId, allowedStatuses);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long subTaskId, Long newAgentId) {
        stateMachineService.reassign(subTaskId, newAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean claimAtomic(Long subTaskId, Long agentId) {
        return stateMachineService.claimAtomic(subTaskId, agentId);
    }

    // ══════════════════════════════════════════════════════════════
    //  委托：租约 / 回收 / 巡检 → SubTaskLeaseService
    // ══════════════════════════════════════════════════════════════

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int renewCurrentNodeLeases(OffsetDateTime newLeaseUntil, int limit) {
        return leaseService.renewCurrentNodeLeases(newLeaseUntil, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reclaimExpiredLeases(int limit) {
        return leaseService.reclaimExpiredLeases(limit);
    }

    @Override
    public List<SubTask> listReviewOrphans(int thresholdSeconds, int limit) {
        return leaseService.listReviewOrphans(thresholdSeconds, limit);
    }

    @Override
    public List<SubTaskView> listReviewOrphanViews(int thresholdSeconds, int limit) {
        return leaseService.listReviewOrphanViews(thresholdSeconds, limit);
    }

    @Override
    public List<SubTask> listPausedBefore(Long agentId, OffsetDateTime expiredBefore) {
        return leaseService.listPausedBefore(agentId, expiredBefore);
    }

    @Override
    public List<SubTask> listTimedOutAssigned(OffsetDateTime deadline, int limit) {
        return leaseService.listTimedOutAssigned(deadline, limit);
    }

    @Override
    public int incrementExternalFallbackCount(Long subTaskId, OffsetDateTime now) {
        return leaseService.incrementExternalFallbackCount(subTaskId, now);
    }
}
