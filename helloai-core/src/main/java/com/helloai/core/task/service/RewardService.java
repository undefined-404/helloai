package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.RewardLog;
import com.helloai.core.task.mapper.RewardLogMapper;
import com.helloai.core.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewardService extends ServiceImpl<RewardLogMapper, RewardLog> {

    private final AgentService agentService;

    @Transactional(rollbackFor = Exception.class)
    public void addReward(Long agentId, String reason, int delta, Long subTaskId) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }

        int newBalance = agent.getScore() + delta;

        RewardLog logEntity = new RewardLog();
        logEntity.setAgentId(agentId);
        logEntity.setSubTaskId(subTaskId);
        logEntity.setReason(reason);
        logEntity.setDelta(delta);
        logEntity.setBalance(newBalance);
        save(logEntity);

        agent.setScore(newBalance);
        agentService.updateById(agent);

        log.info("积分变动: agentId={}, delta={}, balance={}, reason={}",
                agentId, delta, newBalance, reason);
    }

    /**
     * 分页查询全局积分流水，按创建时间倒序。
     */
    public IPage<RewardLog> listAllLogs(int page, int pageSize) {
        return lambdaQuery()
                .orderByDesc(RewardLog::getCreateTime)
                .page(new Page<>(page, pageSize));
    }

    public Map<String, Object> getAgentScoreSummary(Long agentId) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }

        List<RewardLog> logs = lambdaQuery()
                .eq(RewardLog::getAgentId, agentId)
                .list();

        long rewardCount = logs.stream().filter(l -> l.getDelta() > 0).count();
        long penaltyCount = logs.stream().filter(l -> l.getDelta() < 0).count();

        Map<String, Object> result = new HashMap<>();
        result.put("agentId", agentId);
        result.put("agentName", agent.getName());
        result.put("totalScore", agent.getScore());
        result.put("rewardCount", rewardCount);
        result.put("penaltyCount", penaltyCount);
        result.put("totalRecords", logs.size());
        return result;
    }
}
