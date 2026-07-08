package com.helloai.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
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
import java.time.OffsetDateTime;
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
    private final TaskTimelineService taskTimelineService;

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
        // 阶段 0 补全默认值
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        agent.setCapabilities(new java.util.HashMap<>());
        agent.setLabels(new java.util.HashMap<>());
        agent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
        save(agent);
        log.info("Agent 注册成功: name={}, role={}, id={}, accessType={}",
                name, role, agent.getId(), agent.getAccessType());
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
    //  阶段 4.3 SLEEPING 状态管理（v2.4 §4.3）
    //  - sleep：管理员手动暂停 Agent，系统不自动设 SLEEPING
    //  - wake：恢复后设 OFFLINE（让系统心跳自然计算 IDLE/ONLINE，不强行 ONLINE）
    //  - SLEEPING 不写 offline_reason/offline_at（v2.4 行 419）
    //  - 每次操作都写 task_timeline 审计（event_type=agent_sleep/agent_wake, role=SYSTEM）
    // ══════════════════════════════════════════════════════════════

    /**
     * 管理员手动暂停 Agent（v2.4 §4.3）。
     *
     * <p>行为：
     * <ol>
     *   <li>校验当前状态不能是 SLEEPING（避免重复 sleep）</li>
     *   <li>设 online_status=SLEEPING + update_by=operator</li>
     *   <li><b>不动</b> offline_reason/offline_at（v2.4 行 419：SLEEPING 不写此字段）</li>
     *   <li>写 task_timeline 审计（event_type=agent_sleep, role=SYSTEM）</li>
     * </ol>
     *
     * <p>SLEEPING 防护（已实现，本方法不重复校验）：
     * <ul>
     *   <li>HeartbeatService.seen/active 检测到 SLEEPING 不会覆盖 online_status</li>
     *   <li>AgentHealthCheckTask 扫描时跳过 SLEEPING（IS DISTINCT FROM 'SLEEPING'）</li>
     *   <li>AgentMapper.markOfflineIfStale CAS 条件中也防护 SLEEPING</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public Agent sleepAgent(Long agentId, String operator, String reason) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);

        AgentOnlineStatus prev = agent.getOnlineStatus();
        if (prev == AgentOnlineStatus.SLEEPING) {
            throw new BizException("Agent 已经是 SLEEPING 状态，无需重复暂停: id=" + agentId);
        }

        agent.setOnlineStatus(AgentOnlineStatus.SLEEPING);
        agent.setUpdateBy(operator != null && !operator.isBlank() ? operator : "admin");
        updateById(agent);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operator", agent.getUpdateBy());
        if (reason != null && !reason.isBlank()) payload.put("reason", reason);
        payload.put("prev_status", prev != null ? prev.name() : "UNKNOWN");
        payload.put("new_status", AgentOnlineStatus.SLEEPING.name());
        payload.put("at", OffsetDateTime.now().toString());

        taskTimelineService.recordEvent(
                null, null, "agent_sleep", AgentRole.SYSTEM, agentId, payload);

        log.info("Agent 手动暂停: id={}, prev={}, operator={}, reason={}",
                agentId, prev, agent.getUpdateBy(), reason);
        return agent;
    }

    /**
     * 管理员手动恢复 Agent（v2.4 §4.3）。
     *
     * <p>行为：
     * <ol>
     *   <li>校验当前状态必须是 SLEEPING（避免错误恢复）</li>
     *   <li>设 online_status=OFFLINE + update_by=operator（不强行 ONLINE，让系统下次心跳计算）</li>
     *   <li>保持 offline_reason/offline_at 不变（v2.4 行 419）</li>
     *   <li>写 task_timeline 审计（event_type=agent_wake, role=SYSTEM）</li>
     * </ol>
     *
     * <p>为什么 wake 后设 OFFLINE 而不是 ONLINE：v2.4 §4.1 设计原则，三态由 last_seen_at/last_active_at
     * 计算，避免 Agent 还未发送心跳就误判为 ONLINE。下次 heartbeat 调用时 HeartbeatService
     * 会计算为 IDLE/ONLINE。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Agent wakeAgent(Long agentId, String operator, String reason) {
        Agent agent = getById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);

        AgentOnlineStatus prev = agent.getOnlineStatus();
        if (prev != AgentOnlineStatus.SLEEPING) {
            throw new BizException("Agent 不是 SLEEPING 状态，无法唤醒: id=" + agentId + ", current=" + prev);
        }

        agent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
        agent.setUpdateBy(operator != null && !operator.isBlank() ? operator : "admin");
        updateById(agent);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operator", agent.getUpdateBy());
        if (reason != null && !reason.isBlank()) payload.put("reason", reason);
        payload.put("prev_status", prev.name());
        payload.put("new_status", AgentOnlineStatus.OFFLINE.name());
        payload.put("at", OffsetDateTime.now().toString());

        taskTimelineService.recordEvent(
                null, null, "agent_wake", AgentRole.SYSTEM, agentId, payload);

        log.info("Agent 手动唤醒: id={}, operator={}, reason={}", agentId, agent.getUpdateBy(), reason);
        return agent;
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
