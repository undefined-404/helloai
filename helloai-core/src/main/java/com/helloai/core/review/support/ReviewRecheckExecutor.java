package com.helloai.core.review.support;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽检复审执行器（反馈回路，§7.8 拆分：抽检与核验编排分离）。
 *
 * <p>对已 APPROVED 的审查记录换 Reviewer 复判一次：<b>只度量不改状态</b>——
 * 子任务已按原判 DONE/REWORK 推进，复审结果仅写 review_recheck_log
 * （discrepancy=原 APPROVED 复审 REJECTED 的放水标记）+ timeline 观测，
 * 不落 review_record、不改子任务状态；Reviewer 维度 reviewed 计数单独走
 * {@link AgentQualityProfileService#incrementReviewerStats}（best-effort），
 * 执行者画像不受抽检影响。</p>
 *
 * <p>判定复用 {@link ReviewExecutionEngine}（与单审/双审同一执行口径，链路来源传
 * {@link ReviewChannel#RECHECK}，对话流消息走 subtask_recheck_* 前缀与正常核验区分，
 * 且补一条抽检结论消息供执行对话流直接查看）；复审不可判定（LLM 失败/无可用
 * Reviewer）直接跳过，等待下一轮抽检。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRecheckExecutor {

    private final ReviewService reviewService;
    private final SubTaskService subTaskService;
    private final ReviewerPicker reviewerPicker;
    private final TaskTimelineService taskTimelineService;
    private final AgentQualityProfileService agentQualityProfileService;
    private final ReviewExecutionEngine reviewExecutionEngine;
    private final ConversationService conversationService;
    /** `RV-5`：抽检前用快照对账当前证据是否仍是原判定对象。 */
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;

    /**
     * 对已 APPROVED 的审查记录换 Reviewer 复判一次（入口：ReviewerRecheckTask 抽样调度）。
     *
     * @param reviewRecordId 被抽检的审查记录 ID；null / 记录不存在 / 非 APPROVED /
     *                       无可用 Reviewer / 复审不可判定时静默跳过
     */
    public void recheckReviewRecord(Long reviewRecordId) {
        if (reviewRecordId == null) {
            return;
        }
        ReviewRecord record = reviewService.getById(reviewRecordId);
        if (record == null) {
            log.warn("抽检复审跳过：审查记录不存在, reviewRecordId={}", reviewRecordId);
            return;
        }
        if (record.getResult() != ReviewResult.APPROVED) {
            log.debug("抽检复审跳过：仅抽检 APPROVED 记录, reviewRecordId={}, result={}",
                    reviewRecordId, record.getResult());
            return;
        }
        SubTaskView subTask = subTaskService.getView(record.getSubTaskId());
        if (subTask == null) {
            log.warn("抽检复审跳过：子任务不存在, reviewRecordId={}, subTaskId={}",
                    reviewRecordId, record.getSubTaskId());
            return;
        }
        // `RV-5` 判定快照对账：复判读的是「当前视图」，对 REJECTED 记录返工后产出已被覆盖。
        // 快照存在但与当前证据不一致 ⇒ 复判对象已不是原判对象，本轮抽检不构成有效度量
        // （历史行无快照 ⇒ 按一致处理，不把老数据排除出抽检）。
        if (record.getSnapshot() != null && !record.getSnapshot().isEmpty()) {
            ReviewSnapshotMatcher.Drift drift = reviewEvidenceAssembler.checkSnapshotDrift(
                    record.getSnapshot(), subTask);
            if (drift != null && !drift.matched()) {
                log.warn("抽检复审跳过：当前证据与原判定快照不一致（{}），复判对象已不是原判对象: "
                                + "reviewRecordId={}, subTaskId={}",
                        drift.reason(), reviewRecordId, subTask.id());
                recordSkipQuietly(subTask, reviewRecordId, "snapshot_drift", 0,
                        Map.of("drift", drift.reason()));
                return;
            }
        }
        // `RV-4` 抽检独立性：排除原判定人与该子任务全部历史 reviewer，
        // 由「没判过这个子任务的人」复判，否则「一致」可能只是同一个人重复同一个偏差
        Set<Long> excluded = reviewersInvolved(subTask);
        AgentProfileSnapshot reviewer = reviewerPicker.pickSingle(subTask, excluded);
        if (reviewer == null) {
            log.warn("抽检复审跳过：排除历史 reviewer 后无可用核验 Agent, reviewRecordId={}, excluded={}",
                    reviewRecordId, excluded.size());
            recordSkipQuietly(subTask, reviewRecordId, "no_available_reviewer", excluded.size());
            return;
        }
        ReviewExecutionEngine.ExecutionOutcome outcome =
                reviewExecutionEngine.execute(subTask, reviewer, ReviewChannel.RECHECK);
        SubTaskReviewService.ReviewVerdict verdict = outcome.verdict();
        if (verdict == null) {
            log.warn("抽检复审不可判定，跳过等下一轮: reviewRecordId={}", reviewRecordId);
            recordSkipQuietly(subTask, reviewRecordId, "unjudgeable", excluded.size());
            return;
        }
        // `RV-2`：复审判定自相矛盾 ⇒ 本次抽检不构成有效度量，按不可判定跳过（不写 recheck 日志）
        if (outcome.inconclusive()) {
            log.warn("抽检复审判定不可采信（BLOCK），跳过本轮: reviewRecordId={}, blocks={}",
                    reviewRecordId, outcome.blockFindings().size());
            recordSkipQuietly(subTask, reviewRecordId, "inconclusive", excluded.size());
            return;
        }
        boolean pass = Boolean.TRUE.equals(verdict.getPass());
        ReviewResult recheckResult = pass ? ReviewResult.APPROVED : ReviewResult.REJECTED;
        int fallback = pass ? 3 : 1;
        int score = verdict.getScore() != null ? verdict.getScore() : fallback;
        score = Math.max(1, Math.min(5, score));
        // 对话流：补一条抽检结论消息（与正常核验的类型前缀区分开），
        // 声明"只度量不改状态"，避免执行对话流误读为新一轮正式核验
        try {
            String resultText = "## 抽检复审结论（只度量，不改变子任务状态）\n\n"
                    + "- 结果: " + (pass ? "通过（与原判一致）" : "不通过（与原判分歧）") + "\n"
                    + "- 评分: " + score + " / 5\n"
                    + "- 原判定: APPROVED（reviewRecordId=" + reviewRecordId + "）";
            if (verdict.getIssues() != null && !verdict.getIssues().isBlank()) {
                resultText += "\n- 问题: " + verdict.getIssues();
            }
            if (verdict.getComment() != null && !verdict.getComment().isBlank()) {
                resultText += "\n- 评语: " + verdict.getComment();
            }
            conversationService.addMessage(subTask.id(), reviewer.id(),
                    "assistant", "agent", resultText, TimelineEventCode.SUBTASK_RECHECK_RESULT.code());
        } catch (Exception e) {
            log.warn("抽检结论对话流写入失败（不阻断抽检）: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
        // 抽检日志落库（best-effort）：放水率度量与人工复核追溯的唯一事实源
        try {
            reviewService.recordRecheck(reviewRecordId, subTask.id(), ReviewResult.APPROVED,
                    recheckResult, !pass, reviewer.id(), score,
                    verdict.getIssues(), verdict.getComment());
        } catch (Exception e) {
            log.warn("抽检复审落 review_recheck_log 失败: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
        // Reviewer 维度画像计数（best-effort）：复审完成 +1 reviewed；分歧信号留在 log.discrepancy
        try {
            agentQualityProfileService.incrementReviewerStats(reviewer.id(), 1, 0);
        } catch (Exception e) {
            log.warn("抽检 Reviewer 画像计数增量失败: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
        taskTimelineService.recordEvent(subTask.taskId(), subTask.id(),
                pass ? TimelineEventCode.SUB_TASK_RECHECK_CONSISTENT.code() : TimelineEventCode.SUB_TASK_RECHECK_DISCREPANCY.code(),
                AgentRole.REVIEWER, reviewer.id(),
                Map.of("reviewRecordId", reviewRecordId, "originalResult", "APPROVED",
                        "recheckResult", recheckResult.name(), "discrepancy", !pass,
                        "score", score));
        log.info("抽检复审完成: reviewRecordId={}, subTaskId={}, recheckResult={}, reviewerAgentId={}",
                reviewRecordId, subTask.id(), recheckResult, reviewer.id());
    }

    /**
     * 该子任务已参与过判定的全部 Reviewer ID（`RV-4` 排除集）。
     *
     * <p>两个来源合并：</p>
     * <ol>
     *     <li>{@code review_record.reviewer_agent_id}：单审 / 人工审查 / 双审记录归属人；</li>
     *     <li>REVIEWER 角色的 timeline payload（{@code reviewerAgentId} / {@code reviewer1AgentId} /
     *         {@code reviewer2AgentId}）：补上双审中<b>不落 record</b>的第二位评审与历次抽检人。</li>
     * </ol>
     *
     * <p>两处查询均为 best-effort：查询失败只是少排除几个人（退回旧行为），不阻断抽检主链路。</p>
     */
    private Set<Long> reviewersInvolved(SubTaskView subTask) {
        Set<Long> ids = new HashSet<>();
        try {
            List<ReviewRecord> records = reviewService.getBySubTaskId(subTask.id());
            if (records != null) {
                for (ReviewRecord record : records) {
                    if (record.getReviewerAgentId() != null) {
                        ids.add(record.getReviewerAgentId());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取子任务审查记录失败（排除集按空处理）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }
        try {
            List<TaskTimeline> events = taskTimelineService.listBySubTaskId(subTask.id());
            if (events != null) {
                for (TaskTimeline event : events) {
                    if (event.getRole() != AgentRole.REVIEWER || event.getPayload() == null) {
                        continue;
                    }
                    Map<String, Object> payload = event.getPayload();
                    addAgentId(ids, payload.get("reviewerAgentId"));
                    addAgentId(ids, payload.get("reviewer1AgentId"));
                    addAgentId(ids, payload.get("reviewer2AgentId"));
                }
            }
        } catch (Exception e) {
            log.debug("读取子任务时间线失败（排除集按已收集部分处理）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }
        return ids;
    }

    /** payload 里的 Agent ID 是 JSON 反序列化的 Number，容忍 Integer/Long 两种形态。 */
    private static void addAgentId(Set<Long> ids, Object value) {
        if (value instanceof Number number) {
            ids.add(number.longValue());
        }
    }

    /**
     * 抽检跳过事件（`RV-4`：此前「无可用 reviewer / 不可判定」只有日志，时间线上完全不可见）。
     *
     * <p>写入失败只降级告警：抽检是度量旁路，不得因观测失败影响调度。</p>
     *
     * @param reason 跳过原因：{@code no_available_reviewer} / {@code unjudgeable} / {@code inconclusive}
     */
    private void recordSkipQuietly(SubTaskView subTask, Long reviewRecordId, String reason, int excludedCount) {
        recordSkipQuietly(subTask, reviewRecordId, reason, excludedCount, null);
    }

    private void recordSkipQuietly(SubTaskView subTask, Long reviewRecordId, String reason, int excludedCount,
                                   Map<String, Object> extra) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reviewRecordId", reviewRecordId);
            payload.put("reason", reason);
            payload.put("excludedReviewers", excludedCount);
            if (extra != null && !extra.isEmpty()) {
                payload.putAll(extra);
            }
            taskTimelineService.recordEvent(subTask.taskId(), subTask.id(),
                    TimelineEventCode.SUB_TASK_RECHECK_SKIPPED.code(), AgentRole.REVIEWER, null, payload);
        } catch (Exception e) {
            log.warn("抽检跳过事件写入失败（观测降级，不阻断抽检）: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
    }
}
