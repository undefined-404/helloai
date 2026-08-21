package com.helloai.core.agent.quality.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.agent.quality.DefectLabelParser;
import com.helloai.core.agent.quality.dto.RebuildSourceRow;
import com.helloai.core.agent.quality.entity.AgentQualityProfile;
import com.helloai.core.agent.quality.mapper.AgentQualityProfileMapper;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 质量画像服务实现（反馈回路第 1 层）。
 *
 * <p>查询类方法 best-effort：查询异常不向调用方抛出（调度回灌 / Prompt 注入
 * 绝不因画像故障阻断主链路）；rebuild 为独立事务重算兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentQualityProfileServiceImpl
        extends ServiceImpl<AgentQualityProfileMapper, AgentQualityProfile>
        implements AgentQualityProfileService {

    /** 历史表现节 TOP3 驳回原因条数。 */
    private static final int HISTORY_TOP_N = 3;

    @Override
    public AgentQualityProfile getProfile(Long agentId) {
        if (agentId == null) {
            return null;
        }
        try {
            return getOne(new LambdaQueryWrapper<AgentQualityProfile>()
                    .eq(AgentQualityProfile::getAgentId, agentId)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            // 防御式：画像查询异常不向调度/Prompt 链路扩散
            log.debug("画像查询异常（按无画像处理）: agentId={}, err={}", agentId, e.getMessage());
            return null;
        }
    }

    @Override
    public Integer computeQualityScore(Long agentId) {
        AgentQualityProfile profile = getProfile(agentId);
        if (profile == null) {
            return null;
        }
        int reviewed = nz(profile.getReviewedCount());
        if (reviewed == 0) {
            return null;
        }
        int firstReviewed = nz(profile.getFirstReviewedCount());
        int firstPass = nz(profile.getFirstPassCount());
        // 首轮通过率（0~100）；无首轮数据按中性 50 计
        int firstPassRate = firstReviewed > 0 ? firstPass * 100 / firstReviewed : 50;
        // 平均分归一（1~5 → 0~100）：(avg - 1) * 25
        int avgNorm = Math.max(0, Math.min(100, nz(profile.getTotalScore()) * 25 / reviewed - 25));
        int score = (int) Math.round(firstPassRate * 0.5 + avgNorm * 0.5);
        return Math.max(0, Math.min(100, score));
    }

    @Override
    public String renderHistorySection(Long agentId) {
        AgentQualityProfile profile = getProfile(agentId);
        if (profile == null) {
            return "";
        }
        int reviewed = nz(profile.getReviewedCount());
        if (reviewed == 0) {
            return "";
        }
        int approved = nz(profile.getApprovedCount());
        int firstReviewed = nz(profile.getFirstReviewedCount());
        int firstPass = nz(profile.getFirstPassCount());
        int passRate = approved * 100 / reviewed;
        int firstPassRate = firstReviewed > 0 ? firstPass * 100 / firstReviewed : -1;

        StringBuilder sb = new StringBuilder();
        sb.append("## 你的历史表现\n\n");
        sb.append("- 累计评审 ").append(reviewed).append(" 次，通过率 ").append(passRate).append("%");
        if (firstPassRate >= 0) {
            sb.append("，一次通过率 ").append(firstPassRate).append("%");
        }
        sb.append("\n");
        List<String> topDefects = topDefectLabels(profile.getIssueDefectStats());
        if (!topDefects.isEmpty()) {
            sb.append("- 最常见驳回原因 TOP").append(topDefects.size()).append("：")
                    .append(String.join("；", topDefects)).append("\n");
        }
        sb.append("- 本轮提醒：请对照验收标准逐条自查，提交前确认交付物已物化、证据链完整\n");
        return sb.toString();
    }

    /** 缺陷标签计数降序取 TOP N（计数相同按标签字典序，输出稳定）。 */
    private List<String> topDefectLabels(Map<String, Integer> stats) {
        if (stats == null || stats.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(stats.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(nz(b.getValue()), nz(a.getValue()));
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < Math.min(HISTORY_TOP_N, entries.size()); i++) {
            labels.add(entries.get(i).getKey());
        }
        return labels;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuild(Long agentId) {
        if (agentId == null) {
            return;
        }
        List<RebuildSourceRow> rows = baseMapper.selectRebuildSource(agentId);
        if (rows == null || rows.isEmpty()) {
            // 名下无评审记录：删除画像行（若存在），重算结果与数据源一致
            remove(new LambdaQueryWrapper<AgentQualityProfile>()
                    .eq(AgentQualityProfile::getAgentId, agentId));
            log.info("画像重算：agentId={} 无评审记录，画像已清除", agentId);
            return;
        }

        int reviewed = 0;
        int approved = 0;
        int firstReviewed = 0;
        int firstPass = 0;
        int totalScore = 0;
        int reworkSum = 0;
        Long lastId = null;
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (RebuildSourceRow row : rows) {
            String result = row.getResult();
            int score = nz(row.getScore());
            int round = Math.max(nz(row.getRound()), 1);
            boolean isApproved = "APPROVED".equals(result);
            reviewed++;
            totalScore += score;
            if (isApproved) {
                approved++;
            }
            if (round == 1) {
                firstReviewed++;
                if (isApproved) {
                    firstPass++;
                }
            }
            reworkSum += Math.max(round - 1, 0);
            DefectLabelParser.parse(row.getIssues() != null ? row.getIssues() : "").forEach((k, v) -> stats.merge(k, v, Integer::sum));
            Long recordId = row.getRecordId();
            if (recordId != null) {
                lastId = recordId;
            }
        }

        AgentQualityProfile profile = getProfile(agentId);
        if (profile == null) {
            profile = new AgentQualityProfile();
            profile.setAgentId(agentId);
            profile.setReviewerReviewedCount(0);
            profile.setReviewerDisagreementCount(0);
        }
        profile.setReviewedCount(reviewed);
        profile.setApprovedCount(approved);
        profile.setFirstReviewedCount(firstReviewed);
        profile.setFirstPassCount(firstPass);
        profile.setTotalScore(totalScore);
        profile.setReworkRoundSum(reworkSum);
        profile.setIssueDefectStats(stats);
        profile.setLastReviewRecordId(lastId);
        saveOrUpdate(profile);
        log.info("画像重算完成: agentId={}, reviewed={}, approved={}, lastReviewRecordId={}",
                agentId, reviewed, approved, lastId);
    }

    /** Integer null 安全取 0。 */
    private static int nz(Integer v) {
        return v != null ? v : 0;
    }
}
