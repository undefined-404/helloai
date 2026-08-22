package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.entity.RewardLog;
import com.helloai.core.task.service.ActivityLogService;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.SubTaskService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 工作量与积分统计组件。
 *
 * <p>从 {@code AgentServiceImpl} 拆分（§7.8 类规模红线）：汇总 workloadStats /
 * inProgressCount / scoreRank / 积分明细 / 活动日志 等只读统计查询。</p>
 *
 * <p>阶段五 task↔agent 事件解耦：task 域数据一律经 task 域服务接口读取
 * （SubTaskService / RewardService / ActivityLogService），本组件不再直捅
 * task.mapper；仅 Agent 自身表（AgentMapper）仍属 agent 域。</p>
 */
@Service
public class AgentStatsService {

    private final AgentMapper agentMapper;
    private final SubTaskService subTaskService;
    private final RewardService rewardService;
    private final ActivityLogService activityLogService;

    public AgentStatsService(AgentMapper agentMapper,
                             SubTaskService subTaskService,
                             RewardService rewardService,
                             ActivityLogService activityLogService) {
        this.agentMapper = agentMapper;
        this.subTaskService = subTaskService;
        this.rewardService = rewardService;
        this.activityLogService = activityLogService;
    }

    /**
     * Agent 工作量统计（assigned/inProgress/done/blocked/review 计数）。
     */
    public Map<String, Integer> workloadStats(Long agentId) {
        return subTaskService.countByStatusForAgent(agentId);
    }

    /**
     * Agent 进行中的子任务数。
     */
    public int inProgressCount(Long agentId) {
        return subTaskService.countByStatusForAgent(agentId).getOrDefault("inProgressCount", 0);
    }

    /**
     * Agent 积分排名（并列按同分取同一名次）。
     */
    public int scoreRank(Long agentId) {
        Agent self = agentMapper.selectById(agentId);
        if (self == null || self.getScore() == null) return 0;
        Long higher = agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>().gt(Agent::getScore, self.getScore()));
        return higher == null ? 1 : (int) (higher + 1);
    }

    /**
     * Agent 积分明细分页。
     */
    public Page<RewardLog> getScoreLogs(Long agentId, int pageNum, int pageSize) {
        return (Page<RewardLog>) rewardService.listLogsByAgent(agentId, pageNum, pageSize);
    }

    /**
     * Agent 活动日志分页（可按 action 过滤）。
     */
    public Page<ActivityLog> getActivityLogs(Long agentId, int pageNum, int pageSize, String action) {
        return (Page<ActivityLog>) activityLogService.listByAgent(agentId, action, pageNum, pageSize);
    }

    /**
     * Agent 关联统计（删除前风险提示 / 详情页关联计数）。
     *
     * <p>原实现位于 {@code AgentServiceImpl.getRelatedCounts}（直捅 4 个 task.mapper），
     * 阶段五收口到本组件并经 task 域服务接口取数。</p>
     *
     * @param agentId Agent ID
     * @return 各关联表计数 Map（含 agentId / agentName）
     */
    public Map<String, Object> getRelatedCounts(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("agentId", agentId);
        counts.put("agentName", agent.getName());
        // 计数统一压成 Integer：AdminAgentController 侧按 (Integer) 强转，Long 会 500
        counts.put("subTaskCount", (int) subTaskService.countByAssignedAgent(agentId));
        counts.put("reviewCount", (int) subTaskService.countReviewByReviewerAgent(agentId));
        counts.put("rewardCount", (int) rewardService.countByAgent(agentId));
        counts.put("activityCount", (int) activityLogService.countByAgent(agentId));
        return counts;
    }
}
