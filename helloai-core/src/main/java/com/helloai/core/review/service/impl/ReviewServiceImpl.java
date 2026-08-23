package com.helloai.core.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.quality.DefectLabelParser;
import com.helloai.core.agent.quality.QualityProfileUpdater;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.review.dto.DefectDistribution;
import com.helloai.core.review.dto.QualityTrendPoint;
import com.helloai.core.review.dto.ReviewerLeniency;
import com.helloai.core.review.dto.ReworkRoundPoint;
import com.helloai.core.review.entity.ReviewRecheckLog;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.review.mapper.ReviewRecheckLogMapper;
import com.helloai.core.review.mapper.ReviewRecordMapper;
import com.helloai.core.review.service.ReviewService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final AgentService agentService;
    private final ExecutionCommandService executionCommandService;
    /** 反馈回路第 1 层：review_record 落库后同事务增量维护质量画像（best-effort 不阻断）。 */
    private final QualityProfileUpdater qualityProfileUpdater;
    /** 反馈回路 Phase 4：抽检候选查询与抽检日志落库（review_recheck_log，同域 Mapper）。 */
    private final ReviewRecheckLogMapper reviewRecheckLogMapper;

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

        // 反馈回路第 1 层：画像增量维护与 review_record 同事务（失败不阻断审查主链路）
        qualityProfileUpdater.onReviewRecordPersisted(executorAgentId,
                record.getId(), record.getRound(), record.getResult(),
                record.getScore(), record.getIssues());

        if (result == ReviewResult.APPROVED) {
            // 修复: APPROVED 走 complete() 触发 5 因子隐式评分（score_factors/composite_score/score_grade/completed_at + reward_log）
            subTaskService.complete(subTaskId);
        } else {
            // §6.57 人工驳回 = 用户拍板开启新一轮：reworkFresh 重置返工计数并清除人工介入标记，
            // 避免改派后的新执行者提交时仍命中 skip_max_rework 跳过自动核验、无节点流转
            subTaskService.reworkFresh(subTaskId, reworkAgentId);
            // §6.100 人工驳回改派内循环闭合：对 API_KEY_LLM 执行者补发执行命令。
            // 执行链完全由 execution_record 驱动，改派不补命令则子任务永久停留 REWORK 卡死
            // （与 SubTaskReviewServiceImpl.rejectAndRework 自动驳回补发范式对齐）。
            Long targetExecutor = reworkAgentId != null ? reworkAgentId : subTask.getAssignedAgentId();
            if (targetExecutor != null) {
                Agent executor = agentService.getById(targetExecutor);
                if (executor != null && executor.getAccessType() == AgentAccessType.API_KEY_LLM) {
                    try {
                        executionCommandService.createAssignedCommand(subTaskId, targetExecutor, "manual-review-rework");
                        log.info("人工驳回返工执行命令已下发: subTaskId={}, executorAgentId={}",
                                subTaskId, targetExecutor);
                    } catch (Exception e) {
                        log.warn("人工驳回返工执行命令下发失败（子任务停留 REWORK 等兜底）: subTaskId={}, err={}",
                                subTaskId, e.getMessage());
                    }
                }
            }
        }

        // 简易奖励（与原 行为兼容：按 review.score 直接加减固定分）
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

        // 反馈回路第 1 层：画像增量维护与 review_record 同事务（失败不阻断核验主链路）。
        // 执行者维度取落库时刻 sub_task.assigned_agent_id 归属
        Long executorAgentId = null;
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask != null) {
            executorAgentId = subTask.getAssignedAgentId();
        }
        qualityProfileUpdater.onReviewRecordPersisted(executorAgentId,
                record.getId(), record.getRound(), record.getResult(),
                record.getScore(), record.getIssues());

        log.info("自动核验落库: subTaskId={}, result={}, score={}, round={}",
                subTaskId, result.name(), score, round);
        return record;
    }

    @Override
    public long countRecheckCandidates(OffsetDateTime since) {
        if (since == null) {
            since = OffsetDateTime.now().minusYears(100);
        }
        return reviewRecheckLogMapper.countRecheckCandidates(since);
    }

    @Override
    public List<Long> listRecheckCandidateIds(OffsetDateTime since, int limit) {
        if (since == null) {
            since = OffsetDateTime.now().minusYears(100);
        }
        List<Long> ids = reviewRecheckLogMapper.selectRecheckCandidateIds(since, limit);
        return ids != null ? ids : List.of();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewRecheckLog recordRecheck(Long reviewRecordId, Long subTaskId,
                                          ReviewResult originalResult, ReviewResult recheckResult,
                                          boolean discrepancy, Long reviewerAgentId,
                                          Integer score, String issues, String comment) {
        ReviewRecheckLog recheckLog = new ReviewRecheckLog();
        recheckLog.setReviewRecordId(reviewRecordId);
        recheckLog.setSubTaskId(subTaskId);
        recheckLog.setOriginalResult(originalResult);
        recheckLog.setRecheckResult(recheckResult);
        recheckLog.setDiscrepancy(discrepancy ? 1 : 0);
        recheckLog.setReviewerAgent(reviewerAgentId);
        recheckLog.setScore(score != null ? score : 0);
        recheckLog.setIssues(issues);
        recheckLog.setComment(comment);
        // 本 ServiceImpl 泛型为 ReviewRecord，抽检日志经同域 Mapper 直插（同事务）
        reviewRecheckLogMapper.insert(recheckLog);
        log.info("抽检复审落库: reviewRecordId={}, subTaskId={}, original={}, recheck={}, discrepancy={}",
                reviewRecordId, subTaskId, originalResult, recheckResult, discrepancy);
        return recheckLog;
    }

    @Override
    public List<QualityTrendPoint> statsTrendSource(int days) {
        List<QualityTrendPoint> points = baseMapper.selectTrendSource(normalizeDays(days));
        return points != null ? points : List.of();
    }

    @Override
    public List<DefectDistribution> statsDefectDistribution(int days) {
        List<String> issuesList = baseMapper.selectIssuesForStats(normalizeDays(days));
        if (issuesList == null || issuesList.isEmpty()) {
            return List.of();
        }
        // 解析口径与画像增量/rebuild 完全一致（DefectLabelParser），保证看板对账一致
        Map<String, Long> merged = new LinkedHashMap<>();
        for (String issues : issuesList) {
            DefectLabelParser.parse(issues).forEach((label, count) ->
                    merged.merge(label, count.longValue(), Long::sum));
        }
        return merged.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new DefectDistribution(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public List<ReworkRoundPoint> statsReworkDistribution(int days) {
        List<ReworkRoundPoint> points = baseMapper.selectReworkDistribution(normalizeDays(days));
        return points != null ? points : List.of();
    }

    @Override
    public List<ReviewerLeniency> statsReviewerLeniency(int days) {
        List<ReviewerLeniency> rows = baseMapper.selectReviewerLeniency(normalizeDays(days));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 审查者补名：批量查 agent 表，缺失（如已删除）显示 ID 字符串
        List<Long> ids = rows.stream().map(ReviewerLeniency::reviewerAgentId).toList();
        Map<Long, String> names = agentService.listByIds(ids).stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName, (a, b) -> a));
        return rows.stream().map(r -> {
            Long id = r.reviewerAgentId();
            String name = names.get(id);
            return new ReviewerLeniency(id, name != null ? name : String.valueOf(id),
                    r.reviewedCount(), r.approveRate(), r.avgScore());
        }).toList();
    }

    /** 看板统计窗口归一：&lt;=0 按 30 天兜底。 */
    private int normalizeDays(int days) {
        return days > 0 ? days : 30;
    }
}
