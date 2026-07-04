package com.helloai.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.*;
import com.helloai.core.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

/**
 * Agent 核心服务。负责 Agent 注册、CRUD、enrichment 查询、级联删除。
 * 为避免循环依赖，本 Service 直接注入 Mapper 而非依赖其他 Service。
 *
 * @see Agent
 * @see AgentMapper
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService extends ServiceImpl<AgentMapper, Agent> {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SubTaskMapper subTaskMapper;
    private final RewardLogMapper rewardLogMapper;
    private final ActivityLogMapper activityLogMapper;
    private final PatrolRecordMapper patrolRecordMapper;
    private final ReviewRecordMapper reviewRecordMapper;

    // ══════════════════════════════════════════════════════════════
    //  注册 / 基础 CRUD（不变）
    // ══════════════════════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public Agent register(String name, AgentRole role, String description) {
        var existing = lambdaQuery().eq(Agent::getName, name).one();
        if (existing != null) {
            throw new BizException("名称 '" + name + "' 已被注册");
        }
        Agent agent = new Agent();
        agent.setName(name);
        agent.setRole(role);
        agent.setApiKey("ak_" + generateRandomHex(32));
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setScore(0);
        agent.setRemark(description);
        save(agent);
        log.info("Agent 注册成功: name={}, role={}, id={}", name, role, agent.getId());
        return agent;
    }

    public Agent getByApiKey(String apiKey) {
        return lambdaQuery().eq(Agent::getApiKey, apiKey).one();
    }

    public List<Agent> listByRole(AgentRole role) {
        return lambdaQuery().eq(Agent::getRole, role).list();
    }

    public List<Agent> listActive() {
        return lambdaQuery().eq(Agent::getStatus, AgentStatus.ACTIVE)
                .orderByDesc(Agent::getScore).list();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long agentId, AgentStatus status) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        agent.setStatus(status);
        updateById(agent);
        log.info("Agent 状态变更: id={}, status={}", agentId, status);
    }

    @Transactional(rollbackFor = Exception.class)
    public String resetApiKey(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        String newKey = "ak_" + generateRandomHex(32);
        agent.setApiKey(newKey);
        updateById(agent);
        log.info("Agent API Key 重置: id={}", agentId);
        return newKey;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateAgent(Long agentId, String name, String modelType, String remark) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        if (name != null) agent.setName(name);
        if (modelType != null) agent.setModelType(modelType);
        if (remark != null) agent.setRemark(remark);
        updateById(agent);
        log.info("Agent 信息更新: id={}", agentId);
    }

    // ══════════════════════════════════════════════════════════════
    //  分页列表
    // ══════════════════════════════════════════════════════════════

    public Page<Agent> listAgentsPaged(int pageNum, int pageSize, AgentRole role, AgentStatus status,
                                       String keyword, String sortBy, String sortOrder) {
        var wrapper = new LambdaQueryWrapper<Agent>()
                .eq(role != null, Agent::getRole, role)
                .eq(status != null, Agent::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Agent::getName, keyword)
                        .or().like(Agent::getRemark, keyword));
        if ("score".equals(sortBy)) {
            wrapper.orderBy(true, "asc".equalsIgnoreCase(sortOrder), Agent::getScore);
        } else {
            wrapper.orderBy(true, "asc".equalsIgnoreCase(sortOrder), Agent::getCreateTime);
        }
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    // ══════════════════════════════════════════════════════════════
    //  Workload
    // ══════════════════════════════════════════════════════════════

    public Map<String, Integer> workloadStats(Long agentId) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("assignedCount", 0);
        m.put("inProgressCount", 0);
        m.put("doneCount", 0);
        m.put("blockedCount", 0);
        m.put("reviewCount", 0);
        if (agentId == null) return m;

        List<SubTask> subs = subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getAssignedAgent, agentId)
                        .select(SubTask::getStatus));

        for (SubTask s : subs) {
            if (s.getStatus() == SubTaskStatus.DONE) m.merge("doneCount", 1, Integer::sum);
            else if (s.getStatus() == SubTaskStatus.IN_PROGRESS) m.merge("inProgressCount", 1, Integer::sum);
            else if (s.getStatus() == SubTaskStatus.BLOCKED) m.merge("blockedCount", 1, Integer::sum);
            else if (s.getStatus() == SubTaskStatus.REVIEW) m.merge("reviewCount", 1, Integer::sum);
            else m.merge("assignedCount", 1, Integer::sum);
        }
        return m;
    }

    public int scoreRank(Long agentId) {
        Agent self = getById(agentId);
        if (self == null || self.getScore() == null) return 0;
        long higher = lambdaQuery().gt(Agent::getScore, self.getScore()).count();
        return (int) (higher + 1);
    }

    // ══════════════════════════════════════════════════════════════
    //  详情
    // ══════════════════════════════════════════════════════════════

    public Agent getAgentDetail(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        return agent;
    }

    // ══════════════════════════════════════════════════════════════
    //  关联统计
    // ══════════════════════════════════════════════════════════════

    public Map<String, Object> getRelatedCounts(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("agentId", agentId);
        counts.put("agentName", agent.getName());
        counts.put("subTaskCount", (long) subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getAssignedAgent, agentId)));
        counts.put("reviewCount", (long) reviewRecordMapper.selectCount(
                new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getReviewerAgent, agentId)));
        counts.put("rewardCount", (long) rewardLogMapper.selectCount(
                new LambdaQueryWrapper<RewardLog>().eq(RewardLog::getAgentId, agentId)));
        counts.put("activityCount", (long) activityLogMapper.selectCount(
                new LambdaQueryWrapper<ActivityLog>().eq(ActivityLog::getAgentId, agentId)));
        counts.put("patrolCount", (long) patrolRecordMapper.selectCount(
                new LambdaQueryWrapper<PatrolRecord>().eq(PatrolRecord::getPatrolAgent, agentId)));
        return counts;
    }

    // ══════════════════════════════════════════════════════════════
    //  级联删除
    // ══════════════════════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteAgentCascade(Long agentId, String confirmName) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        if (!agent.getName().equals(confirmName)) {
            throw new BizException("名称不匹配，请确认后重试");
        }

        // 先统计
        int subTaskCount = Integer.parseInt(subTaskMapper.selectCount(
                new LambdaQueryWrapper<SubTask>().eq(SubTask::getAssignedAgent, agentId)).toString());
        int reviewCount = Integer.parseInt(reviewRecordMapper.selectCount(
                new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getReviewerAgent, agentId)).toString());
        int rewardCount = Integer.parseInt(rewardLogMapper.selectCount(
                new LambdaQueryWrapper<RewardLog>().eq(RewardLog::getAgentId, agentId)).toString());
        int activityCount = Integer.parseInt(activityLogMapper.selectCount(
                new LambdaQueryWrapper<ActivityLog>().eq(ActivityLog::getAgentId, agentId)).toString());
        int patrolCount = Integer.parseInt(patrolRecordMapper.selectCount(
                new LambdaQueryWrapper<PatrolRecord>().eq(PatrolRecord::getPatrolAgent, agentId)).toString());

        // unlink 子任务
        subTaskMapper.update(null,
                new LambdaUpdateWrapper<SubTask>()
                        .eq(SubTask::getAssignedAgent, agentId)
                        .set(SubTask::getAssignedAgent, null));

        // 清理级联数据
        rewardLogMapper.delete(new LambdaQueryWrapper<RewardLog>().eq(RewardLog::getAgentId, agentId));
        activityLogMapper.delete(new LambdaQueryWrapper<ActivityLog>().eq(ActivityLog::getAgentId, agentId));
        patrolRecordMapper.delete(new LambdaQueryWrapper<PatrolRecord>().eq(PatrolRecord::getPatrolAgent, agentId));

        removeById(agentId);

        log.info("Agent 级联删除完成: id={}, name={}, reward={}, activity={}, patrol={}",
                agentId, agent.getName(), rewardCount, activityCount, patrolCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentName", agent.getName());
        result.put("subTaskCount", subTaskCount);
        result.put("reviewCount", reviewCount);
        result.put("rewardCount", rewardCount);
        result.put("activityCount", activityCount);
        result.put("patrolCount", patrolCount);
        return result;
    }

    // ══════════════════════════════════════════════════════════════
    //  积分明细 / 活动日志
    // ══════════════════════════════════════════════════════════════

    public Page<RewardLog> getScoreLogs(Long agentId, int pageNum, int pageSize) {
        return rewardLogMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<RewardLog>()
                        .eq(RewardLog::getAgentId, agentId)
                        .orderByDesc(RewardLog::getCreateTime));
    }

    public Page<ActivityLog> getActivityLogs(Long agentId, int pageNum, int pageSize, String action) {
        return activityLogMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<ActivityLog>()
                        .eq(ActivityLog::getAgentId, agentId)
                        .eq(action != null && !action.isBlank(), ActivityLog::getAction, action)
                        .orderByDesc(ActivityLog::getCreateTime));
    }

    // ══════════════════════════════════════════════════════════════
    //  Util
    // ══════════════════════════════════════════════════════════════

    private String generateRandomHex(int length) {
        byte[] bytes = new byte[length / 2];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
