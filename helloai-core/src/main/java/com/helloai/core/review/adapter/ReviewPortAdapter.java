package com.helloai.core.review.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.review.entity.ReviewRecord;
import com.helloai.core.review.mapper.ReviewRecordMapper;
import com.helloai.core.review.mapper.ReviewRecheckLogMapper;
import com.helloai.core.task.port.ReviewFact;
import com.helloai.core.task.port.ReviewPort;
import com.helloai.core.task.port.ReviewSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ReviewPort} 适配实现（§6.146 端口反转配套）：task 域消费方所有审查数据
 * 诉求经本类收口（布尔判定/评分事实/摘要/统计/级联删除），不暴露 review 域实体。
 * 无审查记录返回 null（调用方保持默认值）。
 *
 * <p><b>为什么独立于 {@link ReviewServiceImpl}？</b>端口实现若挂在业务服务上，
 * 会与 task 域构成构造器依赖环（SubTaskServiceImpl → ReviewPort 实现 →
 * SubTaskService → SubTaskServiceImpl）。本类仅依赖同域 Mapper（无任何 task/agent
 * 服务依赖），断环且职责更纯粹：查询适配与审查落库/状态推进分离。</p>
 */
@Service
@RequiredArgsConstructor
public class ReviewPortAdapter implements ReviewPort {

    private final ReviewRecordMapper reviewRecordMapper;
    private final ReviewRecheckLogMapper reviewRecheckLogMapper;

    @Override
    public Boolean isLatestReviewApproved(Long subTaskId) {
        List<ReviewRecord> reviews = listBySubTaskId(subTaskId);
        if (reviews == null || reviews.isEmpty()) {
            return null;
        }
        return reviews.get(reviews.size() - 1).getResult() == ReviewResult.APPROVED;
    }

    @Override
    public List<ReviewFact> listReviewFactsBySubTaskId(Long subTaskId) {
        List<ReviewRecord> reviews = listBySubTaskId(subTaskId);
        if (reviews == null || reviews.isEmpty()) {
            return List.of();
        }
        List<ReviewFact> facts = new ArrayList<>(reviews.size());
        for (ReviewRecord r : reviews) {
            facts.add(new ReviewFact(r.getScore() != null ? r.getScore() : 0,
                    r.getResult() == ReviewResult.APPROVED));
        }
        return facts;
    }

    @Override
    public ReviewSummary latestReviewSummary(Long subTaskId) {
        List<ReviewRecord> reviews = listBySubTaskId(subTaskId);
        if (reviews == null || reviews.isEmpty()) {
            return null;
        }
        ReviewRecord latest = reviews.get(reviews.size() - 1);
        return new ReviewSummary(latest.getResult(), latest.getScore(), latest.getComment());
    }

    @Override
    public long countByReviewerAgentId(Long reviewerAgentId) {
        if (reviewerAgentId == null) {
            return 0;
        }
        return reviewRecordMapper.selectCount(new LambdaQueryWrapper<ReviewRecord>()
                .eq(ReviewRecord::getReviewerAgentId, reviewerAgentId));
    }

    @Override
    public long countByTaskId(Long taskId) {
        return reviewRecordMapper.countByTaskId(taskId);
    }

    @Override
    public int physicalDeleteByTaskId(Long taskId) {
        if (taskId == null) {
            return 0; // 空 id 短路
        }
        // D-1（2026-10-05）：review 域两张子表随任务级联删除。
        // ★★ 子先父后（顺序不可调换）★★：review_recheck_log 有两处 FK——
        //   ① review_recheck_log.review_record_id → review_record(id)（review_record 是**父**）
        //   ② review_recheck_log.sub_task_id      → sub_task(id)
        //   若先删 review_record（父）再删 review_recheck_log（子），当子表仍有引用行时会
        //   撞 fk 约束 → HTTP 500。故必须 **先 review_recheck_log（子）→ 再 review_record（父）**。
        //   两张表均须在 sub_task 行删除之前执行（②的父行）。
        int recheckLogs = reviewRecheckLogMapper.physicalDeleteByTaskId(taskId);
        int records = reviewRecordMapper.physicalDeleteByTaskId(taskId);
        return recheckLogs + records;
    }

    /** 按 round 升序取某子任务全部审查记录（端口各查询共用）。 */
    private List<ReviewRecord> listBySubTaskId(Long subTaskId) {
        return reviewRecordMapper.selectList(new LambdaQueryWrapper<ReviewRecord>()
                .eq(ReviewRecord::getSubTaskId, subTaskId)
                .orderByAsc(ReviewRecord::getRound));
    }
}
