package com.helloai.core.agent.quality;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.port.LastReviewContext;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskReviewContextPort;
import com.helloai.core.agent.port.SubTaskReviewContextPort.BackfillOutcome;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * executorDoneIssues 回填器（反馈回路第 1 层，Phase 1.4）。
 *
 * <p>挂接在 {@code ExecutionResultHandler.handleReport} 成功回写路径（事务提交后异步）：
 * 检测 {@code context.reviewHistory} 最后一轮 {@code executorDoneIssues} 为空且
 * issues 非空 → 调 {@link ExecutorIssueResolutionAssessor} 做 LLM 语义对比 → 回填该轮
 * {@code executorDoneIssues}。</p>
 *
 * <p>防覆盖：LLM 评估在锁外执行（长耗时）；写入前按 subTaskId 分段锁（V38 同款思想）
 * 重读 context，仅当最后一轮 round 未变且 executorDoneIssues 仍为空时落笔，
 * 避免覆盖评估期间新发生的评审轮次或并发回填。全程 best-effort，
 * 三态 timeline（success/skipped/failed）观测。</p>
 *
 * <p><b>2026-10-01（W4）依赖反转</b>：本类原先直接 import
 * {@code task.entity.SubTask} / {@code task.service.SubTaskService} 并自行解析
 * {@code context.reviewHistory}。现改为：
 * <ul>
 *     <li>身份 + 判空走 {@link SubTaskQueryPort#findById}</li>
 *     <li>末轮读取走 {@link SubTaskReviewContextPort#loadLastReviewContext}</li>
 *     <li><b>条件回填整体走不透明命令</b>
 *         {@link SubTaskReviewContextPort#backfillExecutorDoneIssues}（重读 + 判定 + 写
 *         全在 task 域，见备忘录 §16.8）</li>
 * </ul>
 * 行为逐字保留：判定分支、timeline 三态的触发条件与文案均未变；本类的分段锁仍只承担
 * 「并发回填串行化」，不承担正确性判定（与原实现一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutorDoneIssuesBackfiller {

    /** timeline 事件类型（成功/跳过/失败三态 payload，state 字段区分）。 */
    static final String TIMELINE_EVENT = "sub_task_executor_done_issues";

    /** subTaskId 粒度分段锁：回填的"重读-改写"串行化（单实例安全，V38 同款思想）。 */
    private final ConcurrentHashMap<Long, Object> subTaskLocks = new ConcurrentHashMap<>();

    private final SubTaskQueryPort subTaskQueryPort;
    private final SubTaskReviewContextPort reviewContextPort;
    private final TaskTimelinePort taskTimelinePort;
    private final ExecutorIssueResolutionAssessor assessor;

    /** 获取 subTaskId 粒度锁对象（分段锁，无锁清理——锁对象可复用）。 */
    private Object lockFor(Long subTaskId) {
        return subTaskLocks.computeIfAbsent(subTaskId, k -> new Object());
    }

    /**
     * 异步回填入口：由 ExecutionResultHandler 事务提交后调用。
     *
     * @param subTaskId      子任务 ID
     * @param executorOutput 执行者本轮产出正文（成功回写时的原始 output）
     */
    @Async
    public void backfill(Long subTaskId, String executorOutput) {
        if (subTaskId == null) {
            return;
        }
        try {
            SubTaskSnapshot snapshot = subTaskQueryPort.findById(subTaskId);
            if (snapshot == null) {
                return;
            }
            LastReviewContext round = reviewContextPort.loadLastReviewContext(subTaskId);
            if (round == null) {
                recordTimeline(snapshot, "skipped", Map.of("reason", "no_review_history"));
                return;
            }
            if (!isEmpty(round.executorDoneIssues())) {
                // 已回填过（或人工已填），幂等跳过
                return;
            }
            if (round.issues() == null || round.issues().isEmpty()) {
                recordTimeline(snapshot, "skipped", Map.of("reason", "no_issues"));
                return;
            }

            // LLM 语义对比（锁外长耗时；失败返回 null 由 assessor 内部降级）
            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(round.issues(), executorOutput);
            if (result == null) {
                recordTimeline(snapshot, "failed", Map.of("reason", "llm_unavailable_or_parse_error"));
                return;
            }

            // 锁内条件回填：重读/轮次校验/幂等校验/写入整体在 task 域完成
            synchronized (lockFor(subTaskId)) {
                BackfillOutcome outcome = reviewContextPort.backfillExecutorDoneIssues(
                        subTaskId, round.round(), result.getDoneIssues());
                switch (outcome) {
                    case SUB_TASK_MISSING, SKIPPED_ALREADY_FILLED -> {
                        // 与原实现一致：这两种情形静默返回，不记 timeline
                        return;
                    }
                    case SKIPPED_ROUND_CHANGED -> {
                        recordTimeline(snapshot, "skipped", Map.of("reason", "round_changed"));
                        return;
                    }
                    case WRITTEN -> {
                        // 与原实现一致：进入写入分支后继续记 success
                    }
                }
            }
            recordTimeline(snapshot, "success", Map.of(
                    "doneIssues", result.getDoneIssues(),
                    "reason", result.getReason() != null ? result.getReason() : ""));
        } catch (Exception e) {
            // 防御式：回填链路任何异常不向调用方扩散
            log.warn("executorDoneIssues 回填异常（不阻断主链路）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    private void recordTimeline(SubTaskSnapshot snapshot, String state, Map<String, Object> extra) {
        try {
            Map<String, Object> payload = new HashMap<>(extra != null ? extra : Map.of());
            payload.put("state", state);
            taskTimelinePort.recordEvent(
                    snapshot.taskId(),
                    snapshot.id(),
                    TIMELINE_EVENT,
                    AgentRole.REVIEWER,
                    null,
                    payload);
        } catch (Exception e) {
            log.debug("executorDoneIssues timeline 记录失败（忽略）: subTaskId={}, err={}",
                    snapshot.id(), e.getMessage());
        }
    }

    private static boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }
}
