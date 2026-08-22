package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.RewardLog;
import com.helloai.core.task.mapper.RewardLogMapper;
import com.helloai.core.task.service.RewardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分奖励服务实现：积分流水写入、查询与 Agent 积分摘要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardServiceImpl extends ServiceImpl<RewardLogMapper, RewardLog>
        implements RewardService {

    // 懒解析打破循环：AgentServiceImpl 依赖 RewardService（阶段五级联统计/清理收口），
    // 直接注入 AgentService 会形成 agentServiceImpl ↔ rewardServiceImpl 构造器环
    private final ObjectProvider<AgentService> agentServiceProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addReward(Long agentId, String reason, int delta, Long subTaskId) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            throw new BizException("Agent 服务不可用");
        }
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

    @Override
    public IPage<RewardLog> listAllLogs(int page, int pageSize) {
        return lambdaQuery()
                .orderByDesc(RewardLog::getCreateTime)
                .page(new Page<>(page, pageSize));
    }

    @Override
    public Map<String, Object> getAgentScoreSummary(Long agentId) {
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            throw new BizException("Agent 服务不可用");
        }
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

    // ══════════════════════════════════════════════════════════════
    //  阶段五 agent→task.mapper 清零承接（统计 / 物理删除）
    // ══════════════════════════════════════════════════════════════

    @Override
    public IPage<RewardLog> listLogsByAgent(Long agentId, int page, int pageSize) {
        return lambdaQuery()
                .eq(RewardLog::getAgentId, agentId)
                .orderByDesc(RewardLog::getCreateTime)
                .page(new Page<>(page, pageSize));
    }

    @Override
    public long countByAgent(Long agentId) {
        if (agentId == null) return 0;
        return lambdaQuery().eq(RewardLog::getAgentId, agentId).count();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int physicalDeleteByAgent(Long agentId) {
        return baseMapper.physicalDeleteByAgentId(agentId);
    }
}
