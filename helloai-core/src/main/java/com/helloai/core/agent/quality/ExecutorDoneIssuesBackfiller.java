package com.helloai.core.agent.quality;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutorDoneIssuesBackfiller {

    /** timeline 事件类型（成功/跳过/失败三态 payload，state 字段区分）。 */
    static final String TIMELINE_EVENT = "sub_task_executor_done_issues";

    /** subTaskId 粒度分段锁：回填的"重读-改写"串行化（单实例安全，V38 同款思想）。 */
    private final ConcurrentHashMap<Long, Object> subTaskLocks = new ConcurrentHashMap<>();

    private final SubTaskService subTaskService;
    private final TaskTimelineService taskTimelineService;
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
            SubTask subTask = subTaskService.getById(subTaskId);
            if (subTask == null) {
                return;
            }
            ReviewRound round = peekLastRound(subTask);
            if (round == null) {
                recordTimeline(subTask, "skipped", Map.of("reason", "no_review_history"));
                return;
            }
            if (!isEmpty(round.executorDoneIssues)) {
                // 已回填过（或人工已填），幂等跳过
                return;
            }
            if (round.issues == null || round.issues.isEmpty()) {
                recordTimeline(subTask, "skipped", Map.of("reason", "no_issues"));
                return;
            }

            // LLM 语义对比（锁外长耗时；失败返回 null 由 assessor 内部降级）
            ExecutorIssueResolutionAssessor.IssueResolutionResult result =
                    assessor.assess(round.issues, executorOutput);
            if (result == null) {
                recordTimeline(subTask, "failed", Map.of("reason", "llm_unavailable_or_parse_error"));
                return;
            }

            // 锁内重读防覆盖：round 变更或已被并发回填时放弃写入
            synchronized (lockFor(subTaskId)) {
                SubTask fresh = subTaskService.getById(subTaskId);
                if (fresh == null) {
                    return;
                }
                ReviewRound freshRound = peekLastRound(fresh);
                if (freshRound == null || freshRound.round != round.round) {
                    recordTimeline(fresh, "skipped", Map.of("reason", "round_changed"));
                    return;
                }
                if (!isEmpty(freshRound.executorDoneIssues)) {
                    return;
                }
                writeDoneIssues(fresh, freshRound.round, result.getDoneIssues());
            }
            recordTimeline(subTask, "success", Map.of(
                    "doneIssues", result.getDoneIssues(),
                    "reason", result.getReason() != null ? result.getReason() : ""));
        } catch (Exception e) {
            // 防御式：回填链路任何异常不向调用方扩散
            log.warn("executorDoneIssues 回填异常（不阻断主链路）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    /** 锁内写入：拷贝 reviewHistory，定位最后一轮，覆写 executorDoneIssues 后落库。 */
    @SuppressWarnings("unchecked")
    private void writeDoneIssues(SubTask subTask, int targetRound, List<String> doneIssues) {
        Map<String, Object> ctx = new HashMap<>(
                subTask.getContext() != null ? subTask.getContext() : Map.of());
        List<Map<String, Object>> history = new ArrayList<>();
        Object existing = ctx.get("reviewHistory");
        if (existing instanceof List<?> existList) {
            for (Object o : existList) {
                if (o instanceof Map<?, ?> m) {
                    history.add(new HashMap<>((Map<String, Object>) m));
                }
            }
        }
        if (history.isEmpty()) {
            return;
        }
        Map<String, Object> last = history.get(history.size() - 1);
        Object roundObj = last.get("round");
        int lastRound = roundObj instanceof Number n ? n.intValue() : history.size();
        if (lastRound != targetRound) {
            return;
        }
        last.put("executorDoneIssues", doneIssues);
        ctx.put("reviewHistory", history);
        subTask.setContext(ctx);
        subTaskService.updateById(subTask);
        log.info("executorDoneIssues 回填完成: subTaskId={}, round={}, doneCount={}",
                subTask.getId(), targetRound, doneIssues.size());
    }

    /** 提取 reviewHistory 最后一轮（round/issues/executorDoneIssues）；无历史返回 null。 */
    @SuppressWarnings("unchecked")
    private ReviewRound peekLastRound(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx == null) {
            return null;
        }
        Object historyObj = ctx.get("reviewHistory");
        if (!(historyObj instanceof List<?> history) || history.isEmpty()) {
            return null;
        }
        Object last = history.get(history.size() - 1);
        if (!(last instanceof Map<?, ?> m)) {
            return null;
        }
        Object roundObj = m.get("round");
        int round = roundObj instanceof Number n ? n.intValue() : history.size();
        List<String> issues = new ArrayList<>();
        Object issuesObj = m.get("issues");
        if (issuesObj instanceof List<?> issueList) {
            for (Object issue : issueList) {
                if (issue != null) {
                    issues.add(issue.toString());
                }
            }
        } else if (issuesObj != null) {
            issues.add(issuesObj.toString());
        }
        List<String> done = new ArrayList<>();
        if (m.get("executorDoneIssues") instanceof List<?> doneList) {
            for (Object item : doneList) {
                if (item != null) {
                    done.add(item.toString());
                }
            }
        }
        return new ReviewRound(round, issues, done);
    }

    private void recordTimeline(SubTask subTask, String state, Map<String, Object> extra) {
        try {
            Map<String, Object> payload = new HashMap<>(extra != null ? extra : Map.of());
            payload.put("state", state);
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    subTask.getId(),
                    TIMELINE_EVENT,
                    AgentRole.REVIEWER,
                    null,
                    payload);
        } catch (Exception e) {
            log.debug("executorDoneIssues timeline 记录失败（忽略）: subTaskId={}, err={}",
                    subTask.getId(), e.getMessage());
        }
    }

    private static boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }

    /** 最后一轮评审快照（仅回填判定所需字段）。 */
    private record ReviewRound(int round, List<String> issues, List<String> executorDoneIssues) {
    }
}
