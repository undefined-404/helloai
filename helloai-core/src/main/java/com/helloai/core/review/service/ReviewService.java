package com.helloai.core.review.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.review.dto.DefectDistribution;
import com.helloai.core.review.dto.QualityTrendPoint;
import com.helloai.core.review.dto.ReviewerLeniency;
import com.helloai.core.review.dto.ReworkRoundPoint;
import com.helloai.core.review.entity.ReviewRecheckLog;
import com.helloai.core.review.entity.ReviewRecord;

import java.time.OffsetDateTime;
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

    /**
     * 抽检候选计数：窗口内 APPROVED 且未被抽检覆盖的 review_record 数
     * （反馈回路 Phase 4，供 ReviewerRecheckTask 按抽样比例折算批量）。
     *
     * @param since 窗口起点（含）；null 时按全量统计
     */
    long countRecheckCandidates(OffsetDateTime since);

    /**
     * 抽检候选 ID 列表：按落库时间升序（先审先抽）。
     *
     * @param since 窗口起点（含）；null 时按全量统计
     * @param limit 批量上限
     */
    List<Long> listRecheckCandidateIds(OffsetDateTime since, int limit);

    /**
     * 抽检复审落库（反馈回路 Phase 4）：一条抽检日志 = 一次复审判定。
     *
     * <p>抽检只度量不改状态：子任务已按原判推进，本记录仅供放水率统计
     * 与人工复核追溯（discrepancy=1 表示原 APPROVED 复审 REJECTED）。</p>
     *
     * @param reviewRecordId  被抽检的审查记录 ID
     * @param subTaskId       被抽检子任务 ID
     * @param originalResult  原判结果
     * @param recheckResult   复审判定
     * @param discrepancy     放水标记（true=原 APPROVED 复审 REJECTED）
     * @param reviewerAgentId 执行复审的 Reviewer Agent ID
     * @param score           复审评分（1-5）
     * @param issues          复审驳回时的问题描述，可空
     * @param comment         复审意见，可空
     * @return 已持久化的 ReviewRecheckLog
     */
    ReviewRecheckLog recordRecheck(Long reviewRecordId, Long subTaskId,
                                   ReviewResult originalResult, ReviewResult recheckResult,
                                   boolean discrepancy, Long reviewerAgentId,
                                   Integer score, String issues, String comment);

    /**
     * 质量趋势源（Phase 5 看板）：窗口内按天分组的审查统计。
     *
     * @param days 统计窗口（天）；&lt;=0 按 30 兜底
     * @return 按日期升序的趋势点；窗口内无数据返回空列表
     */
    List<QualityTrendPoint> statsTrendSource(int days);

    /**
     * 驳回原因分布（Phase 5 看板）：窗口内 issues 的 {@code [defect]} 标签计数。
     *
     * <p>解析口径与质量画像增量/rebuild 一致（复用 agent 域 DefectLabelParser）。</p>
     *
     * @param days 统计窗口（天）；&lt;=0 按 30 兜底
     * @return 按计数降序（同计数按标签字典序）；无标签返回空列表
     */
    List<DefectDistribution> statsDefectDistribution(int days);

    /**
     * 返工轮次分布（Phase 5 看板）：窗口内按审查轮次分组计数。
     *
     * @param days 统计窗口（天）；&lt;=0 按 30 兜底
     * @return 按 round 升序；窗口内无数据返回空列表
     */
    List<ReworkRoundPoint> statsReworkDistribution(int days);

    /**
     * Reviewer 放水率（Phase 5 看板）：窗口内审查者维度通过率统计。
     *
     * @param days 统计窗口（天）；&lt;=0 按 30 兜底
     * @return 按审查记录数降序；reviewerName 经 agent 域服务补名（缺失显示 ID 字符串）
     */
    List<ReviewerLeniency> statsReviewerLeniency(int days);
}
