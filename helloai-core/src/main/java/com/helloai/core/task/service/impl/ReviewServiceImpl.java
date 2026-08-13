package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 审查服务实现：人工审查、自动核验落库与审查记录查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl extends ServiceImpl<ReviewRecordMapper, ReviewRecord>
        implements ReviewService {

    private final SubTaskService subTaskService;
    private final RewardService rewardService;

    private static final Map<Integer, Integer> SCORE_RULES = Map.of(
            5, 5,   // 超出预期
            4, 5,   // 完全达标
            3, 0,   // 基本达标
            2, -5,  // 部分不足
            1, -5   // 严重不足
    );

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewRecord createReview(Long subTaskId, Long reviewerAgentId,
                                     ReviewResult result, int score,
                                     String issues, String comment, Long reworkAgentId) {

        if (result == ReviewResult.REJECTED && (issues == null || issues.isBlank())) {
            throw new BizException("驳回时必须填写问题描述（issues）");
        }
        if (score < 1 || score > 5) {
            throw new BizException("评分必须 1-5，当前: " + score);
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.REVIEW) {
            throw new BizException("子任务状态为 " + subTask.getStatus() + "，只有 REVIEW 状态才能审查");
        }

        Long executorAgentId = subTask.getAssignedAgentId();

        long round = count(new LambdaQueryWrapper<ReviewRecord>()
                .eq(ReviewRecord::getSubTaskId, subTaskId)) + 1;

        ReviewRecord record = new ReviewRecord();
        record.setSubTaskId(subTaskId);
        record.setReviewerAgentId(reviewerAgentId);
        record.setResult(result);
        record.setScore(score);
        record.setIssues(issues);
        record.setComment(comment);
        record.setRound((int) round);
        save(record);

        if (result == ReviewResult.APPROVED) {
            // v1.1 修复: APPROVED 走 complete() 触发 5 因子隐式评分（score_factors/composite_score/score_grade/completed_at + reward_log）
            subTaskService.complete(subTaskId);
        } else {
            // §6.57 人工驳回 = 用户拍板开启新一轮：reworkFresh 重置返工计数并清除人工介入标记，
            // 避免改派后的新执行者提交时仍命中 skip_max_rework 跳过自动核验、无节点流转
            subTaskService.reworkFresh(subTaskId, reworkAgentId);
        }

        // 简易奖励（与原 v1.0 行为兼容：按 review.score 直接加减固定分）
        if (executorAgentId != null) {
            Integer delta = SCORE_RULES.get(score);
            if (delta != null && delta != 0) {
                rewardService.addReward(executorAgentId, "审查评分 " + score + " 分", delta, subTaskId);
            }
        }

        log.info("审查完成: subTaskId={}, result={}, score={}, round={}",
                subTaskId, result.name(), score, round);
        return record;
    }

    @Override
    public List<ReviewRecord> getBySubTaskId(Long subTaskId) {
        return list(new LambdaQueryWrapper<ReviewRecord>()
                .eq(ReviewRecord::getSubTaskId, subTaskId)
                .orderByAsc(ReviewRecord::getRound));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewRecord recordAutoReview(Long subTaskId, Long reviewerAgentId,
                                         ReviewResult result, int score,
                                         String issues, String comment) {
        long round = count(new LambdaQueryWrapper<ReviewRecord>()
                .eq(ReviewRecord::getSubTaskId, subTaskId)) + 1;

        ReviewRecord record = new ReviewRecord();
        record.setSubTaskId(subTaskId);
        record.setReviewerAgentId(reviewerAgentId);
        record.setResult(result);
        record.setScore(score);
        record.setIssues(issues);
        record.setComment(comment);
        record.setRound((int) round);
        record.setRemark("AUTO_REVIEW");
        save(record);

        log.info("自动核验落库: subTaskId={}, result={}, score={}, round={}",
                subTaskId, result.name(), score, round);
        return record;
    }
}
