package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent SLEEPING/WAKE 状态机组件。
 *
 * <p>从 {@code AgentServiceImpl} 拆分（§7.8 类规模红线）：管理员手动暂停/恢复
 * 及其批量语义（部分成功、无外层事务），每次操作写 task_timeline 审计；
 * SLEEPING 不写 offline_reason/offline_time。</p>
 */
@Slf4j
@Service
public class AgentLifecycleService {

    private final AgentMapper agentMapper;
    private final TaskTimelineService taskTimelineService;

    public AgentLifecycleService(AgentMapper agentMapper, TaskTimelineService taskTimelineService) {
        this.agentMapper = agentMapper;
        this.taskTimelineService = taskTimelineService;
    }

    /**
     * 管理员手动暂停 Agent。
     *
     * <p>行为：
     * <ol>
     *   <li>校验当前状态不能是 SLEEPING（避免重复 sleep）</li>
     *   <li>设 online_status=SLEEPING + update_by=operator</li>
     *   <li><b>不动</b> offline_reason/offline_time（SLEEPING 不写此字段）</li>
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
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);

        AgentOnlineStatus prev = agent.getOnlineStatus();
        if (prev == AgentOnlineStatus.SLEEPING) {
            throw new BizException("Agent 已经是 SLEEPING 状态，无需重复暂停: id=" + agentId);
        }

        applySleepToAgent(agent, prev, operator, reason);
        return agent;
    }

    /**
     * 实际写入 SLEEPING 状态 + task_timeline 审计。
     *
     * <p>被 {@link #sleepAgent(Long, String, String)} 与 {@link #sleepAgentBatch(List, String, String)}
     * 共用：单 agent 走外层事务，批量时每条 autoCommit 独立生效。</p>
     *
     * @param agent    已读取的 Agent 实体（避免再次 selectById）
     * @param prev     调用方读取时的 online_status（用于审计 payload.prev_status）
     * @param operator 操作人；空时回退 "admin"
     * @param reason   可选原因，写入 task_timeline.payload
     */
    private void applySleepToAgent(Agent agent, AgentOnlineStatus prev,
                                   String operator, String reason) {
        // 业务操作人：空值回退 "admin"；用于审计 payload.operator
        String effectiveOperator = operator != null && !operator.isBlank() ? operator : "admin";

        agent.setOnlineStatus(AgentOnlineStatus.SLEEPING);
        agent.setUpdateBy(effectiveOperator); // MetaObjectHandler.updateFill 仍会覆盖为 "system"，无影响
        agentMapper.updateById(agent);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operator", effectiveOperator); // 用业务操作人，不用 agent.getUpdateBy()
        if (reason != null && !reason.isBlank()) payload.put("reason", reason);
        payload.put("prev_status", prev != null ? prev.name() : "UNKNOWN");
        payload.put("new_status", AgentOnlineStatus.SLEEPING.name());
        payload.put("at", OffsetDateTime.now().toString());

        taskTimelineService.recordEvent(
                null, null, "agent_sleep", AgentRole.SYSTEM, agent.getId(), payload);

        log.info("Agent 手动暂停: id={}, prev={}, operator={}, reason={}",
                agent.getId(), prev, effectiveOperator, reason);
    }

    /**
     * 批量暂停 Agent。
     *
     * <p><b>行为契约：</b>
     * <ul>
     *   <li><b>部分成功</b>：逐个处理，单个失败不影响其他 Agent</li>
     *   <li><b>不抛 BizException</b>：所有失败收集到 failed 列表，整体返回 200</li>
     *   <li><b>无外层事务</b>：每条 agent 的 sleep SQL autoCommit 独立生效（自调用不触发代理，
     *       且批量场景不应让一条失败回滚整批）</li>
     * </ul>
     *
     * <p><b>响应结构：</b>
     * <pre>
     * {
     *   "total": 3,
     *   "successCount": 2,
     *   "failedCount": 1,
     *   "succeeded": [{"agentId":1,"agentName":"alice","onlineStatus":"SLEEPING"},
     *                 {"agentId":2,"agentName":"bob","onlineStatus":"SLEEPING"}],
     *   "failed":    [{"agentId":3,"agentName":"carol","reason":"Agent 已是 SLEEPING 状态"}]
     * }
     * </pre>
     *
     * @return 永远非 null；total = agentIds.size()（输入维度，便于 UI 直接展示进度）
     */
    public Map<String, Object> sleepAgentBatch(List<Long> agentIds, String operator, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", agentIds == null ? 0 : agentIds.size());

        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        if (agentIds == null || agentIds.isEmpty()) {
            result.put("successCount", 0);
            result.put("failedCount", 0);
            result.put("succeeded", succeeded);
            result.put("failed", failed);
            return result;
        }

        for (Long agentId : agentIds) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", agentId);

            try {
                Agent agent = agentMapper.selectById(agentId);
                if (agent == null) {
                    item.put("reason", "Agent 不存在");
                    failed.add(item);
                    continue;
                }

                AgentOnlineStatus prev = agent.getOnlineStatus();
                if (prev == AgentOnlineStatus.SLEEPING) {
                    item.put("agentName", agent.getName());
                    item.put("currentStatus", prev.name());
                    item.put("reason", "Agent 已是 SLEEPING 状态");
                    failed.add(item);
                    continue;
                }

                applySleepToAgent(agent, prev, operator, reason);

                item.put("agentName", agent.getName());
                item.put("onlineStatus", agent.getOnlineStatus().name());
                succeeded.add(item);

            } catch (Exception e) {
                log.error("Agent 批量暂停失败: agentId={}", agentId, e);
                item.put("reason", "处理异常: " + e.getMessage());
                failed.add(item);
            }
        }

        result.put("successCount", succeeded.size());
        result.put("failedCount", failed.size());
        result.put("succeeded", succeeded);
        result.put("failed", failed);

        log.info("Agent 批量暂停汇总: total={}, success={}, failed={}, operator={}, reason={}",
                result.get("total"), succeeded.size(), failed.size(), operator, reason);
        return result;
    }

    /**
     * 查询当前 SLEEPING 状态的 Agent。
     *
     * @param role 可选；为 null 时返回所有角色的 SLEEPING Agent
     * @return 按 update_time DESC 排序（最近操作的在前）
     */
    public List<Agent> findSleepingByRole(AgentRole role) {
        return agentMapper.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getOnlineStatus, AgentOnlineStatus.SLEEPING)
                .eq(role != null, Agent::getRole, role)
                .orderByDesc(Agent::getUpdateTime));
    }

    /**
     * 管理员手动恢复 Agent。
     *
     * <p>行为：
     * <ol>
     *   <li>校验当前状态必须是 SLEEPING（避免错误恢复）</li>
     *   <li>设 online_status=OFFLINE + update_by=operator（不强行 ONLINE，让系统下次心跳计算）</li>
     *   <li>保持 offline_reason/offline_time 不变</li>
     *   <li>写 task_timeline 审计（event_type=agent_wake, role=SYSTEM）</li>
     * </ol>
     *
     * <p>为什么 wake 后设 OFFLINE 而不是 ONLINE：§4.1 设计原则，三态由 last_seen_time/last_active_time
     * 计算，避免 Agent 还未发送心跳就误判为 ONLINE。下次 heartbeat 调用时 HeartbeatService
     * 会计算为 IDLE/ONLINE。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Agent wakeAgent(Long agentId, String operator, String reason) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);

        AgentOnlineStatus prev = agent.getOnlineStatus();
        if (prev != AgentOnlineStatus.SLEEPING) {
            throw new BizException("Agent 不是 SLEEPING 状态，无法唤醒: id=" + agentId + ", current=" + prev);
        }

        agent.setOnlineStatus(AgentOnlineStatus.OFFLINE);
        String effectiveOperator = operator != null && !operator.isBlank() ? operator : "admin";
        agent.setUpdateBy(effectiveOperator);
        agentMapper.updateById(agent);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operator", effectiveOperator);
        if (reason != null && !reason.isBlank()) payload.put("reason", reason);
        payload.put("prev_status", prev.name());
        payload.put("new_status", AgentOnlineStatus.OFFLINE.name());
        payload.put("at", OffsetDateTime.now().toString());

        taskTimelineService.recordEvent(
                null, null, "agent_wake", AgentRole.SYSTEM, agentId, payload);

        log.info("Agent 手动唤醒: id={}, operator={}, reason={}", agentId, effectiveOperator, reason);
        return agent;
    }
}
