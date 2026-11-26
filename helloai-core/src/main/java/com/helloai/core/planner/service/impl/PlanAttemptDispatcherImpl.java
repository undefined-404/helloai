package com.helloai.core.planner.service.impl;

import com.helloai.common.constant.PlanAttemptAction;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.planner.entity.PlanAttempt;
import com.helloai.core.planner.mapper.PlanAttemptMapper;
import com.helloai.core.planner.service.PlanAttemptDispatcher;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.planner.support.PlanNodeId;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@link PlanAttemptDispatcher} 实现（`TD-16b`）。
 *
 * <p><b>为什么重派前必须回查任务状态</b>：{@code PlanningTimeoutTask} 与本国同时 30s 周期跑。
 * 若它先把任务从 {@code PLANNING} 回退到 {@code PENDING}（用户超时后要重新触发），
 * 此时再重派就是「把已回退的任务偷偷推回拆解中」——会和用户的重新触发撞车。
 * 所以重派前先判目标是否还在，不在就把本次尝试判死（{@code FAILED_TERMINAL} + 原因），
 * 而不是硬塞进去执行。</p>
 *
 * <p><b>为什么 {@code failed} 计数可能偏少</b>：{@code executeDecompose} 是 {@code @Async}，
 * 跨类调用即异步返回，它内部的异常在本轮的 try-catch 里看不到。这是有意的——
 * 派发器的职责是「保证有人去干」，不是「保证干成」；干成与否则由 attempt 状态体现，
 * 下一轮扫描自然会把失败的捡起来。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanAttemptDispatcherImpl implements PlanAttemptDispatcher {

    private final PlanAttemptService planAttemptService;
    private final PlanAttemptMapper planAttemptMapper;
    private final PlannerDecomposeAsyncService plannerDecomposeAsyncService;
    private final TaskService taskService;

    /**
     * 重派时给的租约时长（分钟）。
     *
     * <p><b>必须小于 {@code helloai.planner.planning-timeout-minutes}（默认 10）</b>：
     * 任务级巡检（{@code PlanningTimeoutTask}）与派发器同为 30s 周期，若本租约更长，
     * 任务级会先动手把 {@code task} 回退 {@code PENDING}，派发器的自愈就永远轮不到。
     * 默认 7 = 任务级 10 留出 3 分钟余量（够两轮扫描 + 一次重派）。
     * <b>改动任一值都要重新检查这个大小关系。</b></p>
     */
    @Value("${helloai.planner.attempt-lease-minutes:7}")
    private int attemptLeaseMinutes;

    @Override
    public DispatchReport dispatchOnce(int limit) {
        int reclaimed = 0;
        try {
            reclaimed = planAttemptService.reclaimHanging(limit);
        } catch (Exception e) {
            log.error("拆解尝试悬挂回收失败，本轮跳过: {}", e.getMessage(), e);
        }

        int redispatched = 0;
        int abandoned = 0;
        int failed = 0;

        // ① 可重试（上次失败但判定重试有意义）② 登记后无人接（T1 提交后 @Async 未跑）
        // 两类合并处理：对「捡起来」这个动作而言它们没有区别，区别只在来的路径。
        List<PlanAttempt> candidates = new java.util.ArrayList<>();
        try {
            candidates.addAll(planAttemptMapper.listRetryable(OffsetDateTime.now(), limit));
        } catch (Exception e) {
            log.error("查可重试拆解尝试失败: {}", e.getMessage(), e);
        }
        try {
            candidates.addAll(planAttemptMapper.listPending(limit));
        } catch (Exception e) {
            log.error("查待派发拆解尝试失败: {}", e.getMessage(), e);
        }

        for (PlanAttempt attempt : candidates) {
            try {
                Outcome outcome = dispatchOne(attempt);
                switch (outcome) {
                    case REDISPATCHED -> redispatched++;
                    case ABANDONED -> abandoned++;
                    case SKIPPED -> { /* 被别人抢走 / 非本派发器负责的动作，不计 */ }
                }
            } catch (Exception e) {
                failed++;
                log.error("重派拆解尝试失败（下轮再试）: attemptId={}, taskId={}, action={}, err={}",
                        attempt.getId(), attempt.getTaskId(), attempt.getAction(), e.getMessage(), e);
            }
        }

        return new DispatchReport(reclaimed, redispatched, abandoned, failed);
    }

    private enum Outcome { REDISPATCHED, ABANDONED, SKIPPED }

    private Outcome dispatchOne(PlanAttempt attempt) {
        // 本批只接管首拆。展开/重生成/确认/废除的失败重放语义各不相同
        // （尤其展开是同步改数据的动作，自动重放会让用户看到「自己没点过」的结果），
        // 留到各自批次落地，此处不越界。
        if (attempt.getAction() != PlanAttemptAction.DECOMPOSE) {
            return Outcome.SKIPPED;
        }

        Long taskId = attempt.getTaskId();
        TaskView task = taskService.getView(taskId);
        // 目标已不存在，或已不在拆解中（超时巡检回退 / 用户取消 / 已确认）⇒ 判死。
        // 不能硬重派：那等于把已回退的任务偷偷推回 PLANNING，与用户重新触发撞车。
        if (task == null || task.status() != TaskStatus.PLANNING) {
            String reason = task == null
                    ? "task not found"
                    : "task no longer in PLANNING (now " + task.status() + ")";
            if (planAttemptService.failTerminal(attempt.getId(), attempt.getAttemptNo(), reason)) {
                log.warn("拆解尝试目标已失效，终止: attemptId={}, taskId={}, reason={}",
                        attempt.getId(), taskId, reason);
                return Outcome.ABANDONED;
            }
            return Outcome.SKIPPED;
        }

        OffsetDateTime leaseUntil = OffsetDateTime.now().plusMinutes(attemptLeaseMinutes);
        // CAS 抢占（带代次，`G-053`）：抢不到说明另一个实例已经接了、或扫描到真正 claim 之间
        // reclaimHanging 已把 attempt_no 加一（本次派发已过时），两种情况都必须放弃。
        if (!planAttemptService.claim(attempt.getId(), attempt.getAttemptNo(),
                PlanNodeId.current(), leaseUntil)) {
            return Outcome.SKIPPED;
        }

        log.info("派发器重新触发拆解: attemptId={}, taskId={}, attemptNo={}",
                attempt.getId(), taskId, attempt.getAttemptNo());
        // 身份随调用传入：异步段不得再读当前值，否则与「重投后号已加一」这件事赛跑（`G-053`）。
        plannerDecomposeAsyncService.executeDecompose(taskId, attempt.getBatchKey(), attempt.getAttemptNo());
        return Outcome.REDISPATCHED;
    }
}
