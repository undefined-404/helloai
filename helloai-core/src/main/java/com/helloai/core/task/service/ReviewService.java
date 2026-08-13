package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.task.entity.ReviewRecord;

import java.util.List;

/**
 * 审查服务接口：人工审查、自动核验落库与审查记录查询。
 */
public interface ReviewService extends IService<ReviewRecord> {

    /**
     * 人工审查子任务：校验状态与参数后落库，并按结果驱动子任务流转与评分奖励。
     *
     * @param subTaskId       子任务 ID
     * @param reviewerAgentId 审查 Agent ID
     * @param result          审查结论（APPROVED / REJECTED）
     * @param score           评分 1-5
     * @param issues          驳回时的问题描述（必填）
     * @param comment         审查意见，可空
     * @param reworkAgentId   驳回后的返工 Agent ID，可空
     * @return 已持久化的 ReviewRecord
     */
    ReviewRecord createReview(Long subTaskId, Long reviewerAgentId,
                              ReviewResult result, int score,
                              String issues, String comment, Long reworkAgentId);

    /**
     * 查询指定子任务的全部审查记录，按轮次升序。
     *
     * @param subTaskId 子任务 ID
     * @return 审查记录列表；不存在时返回空列表
     */
    List<ReviewRecord> getBySubTaskId(Long subTaskId);

    /**
     * 自动核验落库（仅记录，不做状态流转、不做奖励加减分）。
     *
     * <p>与 {@link #createReview} 的分工：自动核验链路由 SubTaskReviewService
     * 自己负责 complete/rework，本方法只把判定结果写进 review_record，
     * 使自动核验与人工审查同表可查；{@code remark="AUTO_REVIEW"} 标记来源。</p>
     *
     * @param subTaskId       子任务 ID
     * @param reviewerAgentId 审查 Agent ID
     * @param result          审查结论
     * @param score           评分 1-5
     * @param issues          问题描述，可空
     * @param comment         审查意见，可空
     * @return 已持久化的 ReviewRecord
     */
    ReviewRecord recordAutoReview(Long subTaskId, Long reviewerAgentId,
                                  ReviewResult result, int score,
                                  String issues, String comment);
}
