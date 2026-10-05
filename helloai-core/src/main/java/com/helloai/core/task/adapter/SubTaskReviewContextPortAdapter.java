package com.helloai.core.task.adapter;

import com.helloai.core.agent.port.LastReviewContext;
import com.helloai.core.agent.port.SubTaskReviewContextPort;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SubTaskReviewContextPort} 的提供方实现（task 域）。
 *
 * <p>承载 {@code SubTask.context.reviewHistory} 的 <b>schema 解析与条件回填</b>
 * （原位于 agent 侧 {@code ExecutorDoneIssuesBackfiller} 的 {@code peekLastRound} /
 * {@code writeDoneIssues}，2026-10-01 W4 迁入本域 —— context 结构本就由本域写入，
 * schema 知识归本域一处维护）。<b>行为逐字保留</b>：判定分支、日志文案与落库时机均未改。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskReviewContextPortAdapter implements SubTaskReviewContextPort {

    private static final String KEY_REVIEW_HISTORY = "reviewHistory";
    private static final String KEY_ROUND = "round";
    private static final String KEY_ISSUES = "issues";
    private static final String KEY_EXECUTOR_DONE_ISSUES = "executorDoneIssues";

    private final SubTaskService subTaskService;

    @Override
    public LastReviewContext loadLastReviewContext(Long subTaskId) {
        SubTask subTask = subTaskService.getById(subTaskId);
        return subTask == null ? null : peekLastRound(subTask);
    }

    @Override
    public BackfillOutcome backfillExecutorDoneIssues(Long subTaskId, int targetRound, List<String> doneIssues) {
        // 锁内重读防覆盖：round 变更或已被并发回填时放弃写入
        SubTask fresh = subTaskService.getById(subTaskId);
        if (fresh == null) {
            return BackfillOutcome.SUB_TASK_MISSING;
        }
        LastReviewContext freshRound = peekLastRound(fresh);
        if (freshRound == null || freshRound.round() != targetRound) {
            return BackfillOutcome.SKIPPED_ROUND_CHANGED;
        }
        if (!isEmpty(freshRound.executorDoneIssues())) {
            return BackfillOutcome.SKIPPED_ALREADY_FILLED;
        }
        writeDoneIssues(fresh, targetRound, doneIssues);
        return BackfillOutcome.WRITTEN;
    }

    /** 提取 reviewHistory 最后一轮（round/issues/executorDoneIssues）；无历史返回 null。 */
    @SuppressWarnings("unchecked")
    private LastReviewContext peekLastRound(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx == null) {
            return null;
        }
        Object historyObj = ctx.get(KEY_REVIEW_HISTORY);
        if (!(historyObj instanceof List<?> history) || history.isEmpty()) {
            return null;
        }
        Object last = history.get(history.size() - 1);
        if (!(last instanceof Map<?, ?> m)) {
            return null;
        }
        Object roundObj = m.get(KEY_ROUND);
        int round = roundObj instanceof Number n ? n.intValue() : history.size();
        List<String> issues = new ArrayList<>();
        Object issuesObj = m.get(KEY_ISSUES);
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
        if (m.get(KEY_EXECUTOR_DONE_ISSUES) instanceof List<?> doneList) {
            for (Object item : doneList) {
                if (item != null) {
                    done.add(item.toString());
                }
            }
        }
        return new LastReviewContext(round, issues, done);
    }

    /** 锁内写入：拷贝 reviewHistory，定位最后一轮，覆写 executorDoneIssues 后落库。 */
    @SuppressWarnings("unchecked")
    private void writeDoneIssues(SubTask subTask, int targetRound, List<String> doneIssues) {
        Map<String, Object> ctx = new HashMap<>(
                subTask.getContext() != null ? subTask.getContext() : Map.of());
        List<Map<String, Object>> history = new ArrayList<>();
        Object existing = ctx.get(KEY_REVIEW_HISTORY);
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
        Object roundObj = last.get(KEY_ROUND);
        int lastRound = roundObj instanceof Number n ? n.intValue() : history.size();
        if (lastRound != targetRound) {
            return;
        }
        last.put(KEY_EXECUTOR_DONE_ISSUES, doneIssues);
        ctx.put(KEY_REVIEW_HISTORY, history);
        subTask.setContext(ctx);
        subTaskService.updateById(subTask);
        log.info("executorDoneIssues 回填完成: subTaskId={}, round={}, doneCount={}",
                subTask.getId(), targetRound, doneIssues.size());
    }

    private static boolean isEmpty(List<String> list) {
        return list == null || list.isEmpty();
    }
}
