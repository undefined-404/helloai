package com.helloai.core.planner.service.impl;

import com.helloai.common.constant.TaskPriority;
import com.helloai.core.planner.entity.PlanDraft;
import com.helloai.core.planner.mapper.PlanDraftMapper;
import com.helloai.core.planner.service.PlanDraftRoundService;
import com.helloai.core.planner.support.DraftCode;
import com.helloai.core.task.port.SubTaskDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * {@link PlanDraftRoundService} 实现：暂存区轮次生命周期（`AD-5b` 起不再写执行表副本）。
 *
 * <p><b>为什么不靠 catch {@code DuplicateKeyException} 做幂等</b>：PostgreSQL 在唯一索引冲突后
 * 会把**整个事务**标记为 aborted（`25P02`），后续任何语句都失败——在一个装着「落库 + 依赖回写
 * + 门禁 + 状态推进」的事务里 catch 它，等于把整批拆解搞挂。所以幂等改成「先查在不在，
 * 在就整批跳过」。同一 {@code batchKey} 只由同一个 attempt 串行调用，窗口可忽略。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDraftRoundServiceImpl implements PlanDraftRoundService {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String STATE_ACTIVE = "ACTIVE";

    private final PlanDraftMapper planDraftMapper;

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int writeDrafts(Long taskId, String batchKey, OffsetDateTime taskCreateTime,
                           List<SubTaskDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return 0;
        }
        // 幂等：该事件已写过则跳过插入
        if (planDraftMapper.countByBatchKey(batchKey) > 0) {
            log.debug("该拆解事件已写暂存区，跳过插入: taskId={}, batchKey={}", taskId, batchKey);
            return 0;
        }
        LocalDate taskDate = toTaskDate(taskCreateTime);
        int round = planDraftMapper.findMaxRound(taskId) + 1;
        int written = 0;
        for (SubTaskDraft d : drafts) {
            if (d == null) {
                continue;
            }
            String code = d.draftCode();
            if (code == null || code.isBlank()) {
                if (d.draftSeq() == null) {
                    log.warn("暂存区草案写入跳过：草案既无 code 也无序号: taskId={}, title={}",
                            taskId, d.title());
                    continue;
                }
                code = DraftCode.root(taskDate, d.draftSeq()).value();
            }
            planDraftMapper.insert(toEntity(d, taskId, code, batchKey, round));
            written++;
        }
        log.info("暂存区草案写入完成: taskId={}, batchKey={}, round={}, rows={}",
                taskId, batchKey, round, written);
        return written;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>用 {@code REQUIRES_NEW} 而不是 {@code MANDATORY}</b>：调用方
     * {@code rejectPlan} 刻意不开事务（它内部要调 {@code planGateRunner.clear} 的
     * `REQUIRES_NEW`，外面再套一层会连接级死锁，见该方法的注释）。本方法与执行表的状态
     * 变更本就不同源——暂存区作废只依赖 `task_plan_draft` 一张表，独立提交即可。</p>
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int discardRound(Long taskId) {
        int discarded = planDraftMapper.discardActiveByTaskId(taskId);
        if (discarded > 0) {
            log.info("暂存区整轮作废: taskId={}, rows={}", taskId, discarded);
        }
        return discarded;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int confirmRound(Long taskId) {
        int confirmed = planDraftMapper.confirmActiveByTaskId(taskId);
        if (confirmed > 0) {
            log.info("暂存区整轮确认: taskId={}, rows={}", taskId, confirmed);
        }
        return confirmed;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int discardBatch(String batchKey) {
        if (batchKey == null || batchKey.isBlank()) {
            return 0;
        }
        int discarded = planDraftMapper.discardByBatchKey(batchKey);
        if (discarded > 0) {
            log.warn("暂存区按批次作废（本批判定不完整）: batchKey={}, rows={}", batchKey, discarded);
        }
        return discarded;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 拆解物料 → 暂存区实体（依赖由落库段的 `applyDependsOn` 回写）。 */
    private PlanDraft toEntity(SubTaskDraft draft, Long taskId, String code, String batchKey, int round) {
        PlanDraft d = new PlanDraft();
        d.setTaskId(taskId);
        d.setCode(code);
        d.setRound(round);
        d.setBatchKey(batchKey);
        d.setNodeState(STATE_ACTIVE);
        d.setTitle(draft.title());
        d.setContent(draft.content());
        d.setDeliverable(draft.deliverable());
        d.setAcceptance(draft.acceptance());
        d.setAcceptanceKind(draft.acceptanceKind());
        d.setPriority(TaskPriority.parse(draft.priority()));
        d.setContract(draft.isContract() == null ? 0 : draft.isContract());
        d.setRequiredSkills(draft.requiredSkills() == null ? List.of() : draft.requiredSkills());
        d.setConstraints(draft.constraints());
        d.setUncertainties(draft.uncertainties() == null ? List.of() : draft.uncertainties());
        d.setContext(draft.context());
        d.setDependsOnCodes(List.of());
        d.setDraftSeq(draft.draftSeq());
        return d;
    }

    private LocalDate toTaskDate(OffsetDateTime taskCreateTime) {
        if (taskCreateTime == null) {
            // 兜底：历史行 create_time 为空。用当天，至少保证 code 格式合法。
            log.warn("任务 create_time 为空，code 日期前缀回落当天");
            return LocalDate.now(ZONE);
        }
        return taskCreateTime.atZoneSameInstant(ZONE).toLocalDate();
    }
}
