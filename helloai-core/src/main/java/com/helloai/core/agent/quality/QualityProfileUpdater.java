package com.helloai.core.agent.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;
import com.helloai.core.agent.quality.mapper.AgentQualityProfileMapper;
import com.helloai.core.task.entity.ReviewRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 质量画像增量维护器（反馈回路第 1 层收口点）。
 *
 * <p>挂在 review_record 落库收口点（{@code ReviewServiceImpl.recordAutoReview}
 * 与 {@code createReview} 两处），与 review 落库同事务增量更新画像：</p>
 * <ul>
 *     <li>执行者维度取 {@code sub_task.assigned_agent_id}（落库时刻归属，改派场景口径自洽）；</li>
 *     <li>统计项 = 轮次计数、首轮通过（round=1 且 APPROVED）、评分累加、
 *         返工轮次（round&gt;1 的轮次贡献）、issues 四元组 [defect] 标签计数；</li>
 *     <li>核心计数走 {@code incrementCore} 单条 UPDATE 原子增量，缺陷标签走
 *         JSONB 原子合并，规避并发读改写竞态；</li>
 *     <li>防重：同一 review_record 重复回调不重复计数（last_review_record_id 递增判定）；</li>
 *     <li>更新失败 best-effort 不阻断 review 主链路（本类所有异常内部吞噬）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityProfileUpdater {

    private static final String UPDATE_BY = "quality-profile";

    private final AgentQualityProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    /**
     * review_record 落库后的画像增量维护（best-effort，不阻断主链路）。
     *
     * <p>NESTED（savepoint）隔离：与 review 落库同事务提交（review 回滚则画像
     * 增量一并回滚），但画像 SQL 失败只回滚本 savepoint，主事务不进入
     * PG aborted 状态（否则同事务内 catch 无效，后续语句报 25P02
     * current transaction is aborted，主链路被拖死）。无事务上下文时
     * 等价 REQUIRED。</p>
     *
     * @param executorAgentId 执行者维度归属（sub_task.assigned_agent_id 落库时刻值）；
     *                        null 时跳过（未指派执行者的评审不产生画像数据）
     * @param record          刚持久化的 ReviewRecord（round/result/score/issues 已就绪）
     */
    @Transactional(propagation = Propagation.NESTED, rollbackFor = Exception.class)
    public void onReviewRecordPersisted(Long executorAgentId, ReviewRecord record) {
        if (executorAgentId == null || record == null || record.getId() == null) {
            log.debug("画像增量跳过：executorAgentId 或 record 缺失, executorAgentId={}", executorAgentId);
            return;
        }
        try {
            applyIncrement(executorAgentId, record);
        } catch (Exception e) {
            // best-effort：画像更新失败绝不阻断 review 主链路
            log.warn("质量画像增量更新失败（已降级跳过）: agentId={}, reviewRecordId={}, err={}",
                    executorAgentId, record.getId(), e.getMessage());
        }
    }

    /** 增量主体：画像行存在则原子增量，不存在则插入初始行（并发冲突时退化为增量）。 */
    private void applyIncrement(Long agentId, ReviewRecord record) {
        AgentQualityProfile existing = findProfile(agentId);
        if (existing == null) {
            try {
                insertInitial(agentId, record);
                return;
            } catch (DuplicateKeyException e) {
                // 并发首次建画像：另一事务已插入，退化为增量路径
                log.debug("画像初始插入冲突，退化为增量: agentId={}", agentId);
                existing = findProfile(agentId);
                if (existing == null) {
                    return;
                }
            }
        }
        // 防重：同一 review_record 重复回调不重复计数
        if (existing.getLastReviewRecordId() != null
                && existing.getLastReviewRecordId() >= record.getId()) {
            log.debug("画像增量跳过：review_record 重复回调, agentId={}, recordId={}, lastId={}",
                    agentId, record.getId(), existing.getLastReviewRecordId());
            return;
        }

        int round = record.getRound() != null ? record.getRound() : 1;
        boolean approved = record.getResult() == ReviewResult.APPROVED;
        int approvedDelta = approved ? 1 : 0;
        int firstReviewedDelta = round == 1 ? 1 : 0;
        int firstPassDelta = (round == 1 && approved) ? 1 : 0;
        int reworkDelta = Math.max(round - 1, 0);
        int scoreDelta = record.getScore() != null ? record.getScore() : 0;

        int rows = profileMapper.incrementCore(agentId, 1, approvedDelta,
                firstReviewedDelta, firstPassDelta, scoreDelta, reworkDelta,
                record.getId(), UPDATE_BY);
        if (rows == 0) {
            log.debug("画像增量更新 0 行（防重或画像缺失）: agentId={}", agentId);
            return;
        }
        mergeDefectStats(agentId, record.getIssues());
    }

    /** 插入初始画像行（首条 review 的贡献直接作为初始值写入）。 */
    private void insertInitial(Long agentId, ReviewRecord record) {
        int round = record.getRound() != null ? record.getRound() : 1;
        boolean approved = record.getResult() == ReviewResult.APPROVED;
        AgentQualityProfile profile = new AgentQualityProfile();
        profile.setAgentId(agentId);
        profile.setReviewedCount(1);
        profile.setApprovedCount(approved ? 1 : 0);
        profile.setFirstReviewedCount(round == 1 ? 1 : 0);
        profile.setFirstPassCount((round == 1 && approved) ? 1 : 0);
        profile.setTotalScore(record.getScore() != null ? record.getScore() : 0);
        profile.setReworkRoundSum(Math.max(round - 1, 0));
        profile.setIssueDefectStats(DefectLabelParser.parse(record.getIssues()));
        profile.setReviewerReviewedCount(0);
        profile.setReviewerDisagreementCount(0);
        profile.setLastReviewRecordId(record.getId());
        profileMapper.insert(profile);
    }

    /** 缺陷标签统计原子合并（解析空则跳过，避免无意义 UPDATE）。 */
    private void mergeDefectStats(Long agentId, String issues) {
        Map<String, Integer> stats = DefectLabelParser.parse(issues);
        if (stats.isEmpty()) {
            return;
        }
        try {
            profileMapper.mergeDefectStats(agentId,
                    objectMapper.writeValueAsString(stats), UPDATE_BY);
        } catch (JsonProcessingException e) {
            log.warn("缺陷标签统计序列化失败（已降级跳过）: agentId={}, err={}", agentId, e.getMessage());
        }
    }

    /** 按 agentId 查活跃画像（deleted=0 由 @TableLogic 自动过滤）。 */
    private AgentQualityProfile findProfile(Long agentId) {
        return profileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentQualityProfile>()
                        .eq(AgentQualityProfile::getAgentId, agentId)
                        .last("LIMIT 1"));
    }
}
