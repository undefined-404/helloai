package com.helloai.core.review.support;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 抽检复审执行器（反馈回路 Phase 4，§7.8 拆分：抽检与核验编排分离）。
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
        SubTask subTask = subTaskService.getById(record.getSubTaskId());
        if (subTask == null) {
            log.warn("抽检复审跳过：子任务不存在, reviewRecordId={}, subTaskId={}",
                    reviewRecordId, record.getSubTaskId());
            return;
        }
        Agent reviewer = reviewerPicker.pickSingle(subTask);
        if (reviewer == null) {
            log.warn("抽检复审跳过：无可用平台内核验 Agent, reviewRecordId={}", reviewRecordId);
            return;
        }
        SubTaskReviewService.ReviewVerdict verdict = reviewExecutionEngine.execute(subTask, reviewer, ReviewChannel.RECHECK);
        if (verdict == null) {
            log.warn("抽检复审不可判定，跳过等下一轮: reviewRecordId={}", reviewRecordId);
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
            conversationService.addMessage(subTask.getId(), reviewer.getId(),
                    "assistant", "agent", resultText, "subtask_recheck_result");
        } catch (Exception e) {
            log.warn("抽检结论对话流写入失败（不阻断抽检）: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
        // 抽检日志落库（best-effort）：放水率度量与人工复核追溯的唯一事实源
        try {
            reviewService.recordRecheck(reviewRecordId, subTask.getId(), ReviewResult.APPROVED,
                    recheckResult, !pass, reviewer.getId(), score,
                    verdict.getIssues(), verdict.getComment());
        } catch (Exception e) {
            log.warn("抽检复审落 review_recheck_log 失败: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
        // Reviewer 维度画像计数（best-effort）：复审完成 +1 reviewed；分歧信号留在 log.discrepancy
        try {
            agentQualityProfileService.incrementReviewerStats(reviewer.getId(), 1, 0);
        } catch (Exception e) {
            log.warn("抽检 Reviewer 画像计数增量失败: reviewRecordId={}, err={}",
                    reviewRecordId, e.getMessage());
        }
        taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                pass ? "sub_task_recheck_consistent" : "sub_task_recheck_discrepancy",
                AgentRole.REVIEWER, reviewer.getId(),
                Map.of("reviewRecordId", reviewRecordId, "originalResult", "APPROVED",
                        "recheckResult", recheckResult.name(), "discrepancy", !pass,
                        "score", score));
        log.info("抽检复审完成: reviewRecordId={}, subTaskId={}, recheckResult={}, reviewerAgentId={}",
                reviewRecordId, subTask.getId(), recheckResult, reviewer.getId());
    }
}
