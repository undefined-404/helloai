package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.executor.AgentSelector.AgentSelectionConstraints;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.observability.CircuitBreakerEventRecorder;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;

import java.util.Map;

/**
 * 弹性调度器。
 *
 * <p>为任务分配提供熔断降级保护：
 * <ul>
 *   <li>外层：@{@link CircuitBreaker}(name="agentDispatch") — 整体调度熔断</li>
 *   <li>内层：per-agentId 独立熔断器（agentDispatch-{agentId}），
 *       以 agentDispatch 实例配置为模板，实现按 Agent 维度熔断</li>
 *   <li>降级：熔断打开或执行失败时，通过 {@link AgentSelector#pickAlternative}
 *       在同角色 Agent 中选择替代者重新分配</li>
 *   <li>分配前执行密集能力预检——执行密集任务（需本机 shell/文件/服务操作）
 *       不分配给无本机执行能力的 Agent，避免"无能力执行 → 幻觉交付 → 审核放行"，
 *       覆盖初始分配 / 离线重分配 / ASSIGNED 超时 / 熔断降级等所有入口</li>
 * </ul>
 *
 * <p>熔断参数（application.yml）：
 * <ul>
 *   <li>failureRateThreshold=30</li>
 *   <li>waitDurationInOpenState=60s</li>
 *   <li>slidingWindowSize=10</li>
 * </ul>
 *
 * @see AgentSelector
 * @see SubTaskService#assignNext(Long, Long)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientDispatcher {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final CircuitBreakerEventRecorder circuitBreakerEventRecorder;
    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final AgentSelector agentSelector;
    private final AgentDispatchProperties agentDispatchProperties;
    private final TaskTimelineService taskTimelineService;

    private static final String DISPATCH_CB_NAME = "agentDispatch";

    /**
     * 弹性分配任务给指定 Agent。
     *
     * <p>执行流程：
     * <ol>
     *   <li>外层 @CircuitBreaker 保护整体调度</li>
     *   <li>按 agentId 获取/创建 per-agent 熔断器</li>
     *   <li>校验 Agent 在线状态（SLEEPING/OFFLINE 立即 fast-fail）</li>
     *   <li>执行密集能力预检（不匹配 → 标记人工介入并 fast-fail 走 fallback）</li>
     *   <li>调用 {@link SubTaskService#assignNext} 执行分配</li>
     *   <li>失败/熔断打开 → fallback 选取替代 Agent</li>
     * </ol>
     *
     * @param agentId   目标 Agent ID
     * @param subTaskId 待分配的子任务 ID
     */
    @CircuitBreaker(name = "agentDispatch", fallbackMethod = "assignNextFallback")
    public void assignNext(Long agentId, Long subTaskId) {
        doAssignNext(agentId, subTaskId, null);
    }

    /**
     * （§6.58 P1）：带任务级约束的弹性分配。
     *
     * <p>在 {@link #assignNext(Long, Long)} 基础上贯穿任务级选人约束
     * （执行者白名单 + 技能 AND 匹配）：首选 fast-fail 后，fallback 替代选人
     * 同样受约束，保证整条分配链不选到任务指定范围外的 Agent。</p>
     *
     * @param agentId     目标 Agent ID
     * @param subTaskId   待分配的子任务 ID
     * @param constraints 任务级选人约束；null 表示不约束（与旧行为一致）
     */
    @CircuitBreaker(name = "agentDispatch", fallbackMethod = "assignNextFallbackWithConstraints")
    public void assignNext(Long agentId, Long subTaskId, AgentSelectionConstraints constraints) {
        doAssignNext(agentId, subTaskId, constraints);
    }

    private void doAssignNext(Long agentId, Long subTaskId, AgentSelectionConstraints constraints) {
        // 按 agentId 维度获取独立熔断器（以 agentDispatch 配置为模板）
        io.github.resilience4j.circuitbreaker.CircuitBreaker perAgentCb = resolvePerAgentCircuitBreaker(agentId);

        // 注册审计监听（幂等，同一 cbName 只注册一次）
        circuitBreakerEventRecorder.registerListener(agentId, perAgentCb);

        perAgentCb.decorateRunnable(() -> {
            Agent agent = agentService.getById(agentId);
            if (agent == null) {
                throw new BizException("Agent 不存在: " + agentId);
            }

            // 首选 Agent 必须满足任务级约束（防御调用方直接指定白名单外/无技能
            // Agent）；不满足时 fast-fail 走 fallback，由受约束的替代选人兜底。
            if (constraints != null && !constraints.allows(agent)) {
                throw new AgentUnavailableException(
                        "首选 Agent 不满足任务级选人约束: agentId=" + agentId + ", subTaskId=" + subTaskId, agentId);
            }

            // 快速失败：跳过明显不可用的 Agent（抛 AgentUnavailableException，不计入熔断统计）
            AgentOnlineStatus onlineStatus = agent.getOnlineStatus();
            if (onlineStatus == AgentOnlineStatus.SLEEPING) {
                throw new AgentUnavailableException("Agent 处于 SLEEPING 状态，不可分配: " + agentId, agentId);
            }
            AgentAccessType accessType = agent.getAccessType();
            if (onlineStatus == AgentOnlineStatus.OFFLINE
                    && (accessType == null || accessType.requiresRuntimeLiveness())) {
                throw new AgentUnavailableException("Agent 处于 OFFLINE 状态，不可分配: " + agentId, agentId);
            }
            // 心跳新鲜度 fast-fail —— online_status 翻 OFFLINE 依赖 5min 阈值 + 60s 巡检，
            // 存在"DB 仍 ONLINE 但 Agent 已死"的滞后窗口；对需运行时存活的 Agent（CLI_CLIENT）
            // 复用 AgentSelector 的心跳新鲜度判断，不新鲜直接走 fallback 选替代 Agent。
            // API_KEY_LLM / WEB_BROWSER 在 isHeartbeatFresh 内部已豁免。
            if (!agentSelector.isHeartbeatFresh(agent)) {
                throw new AgentUnavailableException("Agent 心跳已陈旧（疑似失联），不可分配: " + agentId, agentId);
            }
            // 执行密集能力预检（覆盖所有分配入口）——不匹配时标记人工介入，
            // 抛 AgentUnavailableException 走 fallback 尝试同角色替代；替代也需通过预检。
            if (isExecutionDenseMismatch(agentId, subTaskId, agent)) {
                throw new AgentUnavailableException(
                        "执行密集任务不匹配无本机能力 Agent: agentId=" + agentId + ", subTaskId=" + subTaskId, agentId);
            }

            log.info("弹性调度分配: agentId={}, subTaskId={}, onlineStatus={}",
                    agentId, subTaskId, onlineStatus);
            subTaskService.assignNext(agentId, subTaskId);
        }).run();
    }

    /**
     * 执行密集能力预检：执行密集任务分配给无本机能力 Agent 时，
     * 记 timeline + 幂等标记人工介入，返回 true 表示不匹配（应拒绝分配）。
     */
    private boolean isExecutionDenseMismatch(Long agentId, Long subTaskId, Agent agent) {
        if (!agentDispatchProperties.isFallbackSkipExecutionDense()) {
            return false;
        }
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null || !SubTaskDispatchService.isExecutionDense(subTask)) {
            return false;
        }
        if (SubTaskDispatchService.hasLocalExecutionCapability(agent)) {
            return false;
        }
        taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                "sub_task_dispatch_skip_no_capability", AgentRole.SYSTEM, agentId,
                Map.of("reason", "execution_dense_no_local_capability",
                        "agentId", agentId,
                        "subTaskId", subTaskId));
        subTaskService.markManualIntervention(subTaskId, "dispatch_skip_execution_dense",
                Map.of("agentId", agentId));
        log.warn("V27.1 分配跳过：执行密集任务不可分配给无本机能力 Agent, subTaskId={}, agentId={}",
                subTaskId, agentId);
        return true;
    }

    private io.github.resilience4j.circuitbreaker.CircuitBreaker resolvePerAgentCircuitBreaker(Long agentId) {
        String perAgentName = DISPATCH_CB_NAME + "-" + agentId;
        try {
            return circuitBreakerRegistry.circuitBreaker(perAgentName, DISPATCH_CB_NAME);
        } catch (ConfigurationNotFoundException e) {
            log.warn("熔断模板配置不存在，回退默认配置: template={}, perAgentName={}",
                    DISPATCH_CB_NAME, perAgentName);
            return circuitBreakerRegistry.circuitBreaker(perAgentName);
        }
    }

    /**
     * 熔断降级：选取替代 Agent 重新分配。
     *
     * <p>触发场景：
     * <ul>
     *   <li>熔断器 OPEN — 该 Agent 短期内故障率过高</li>
     *   <li>执行异常 — Agent 不可用或分配失败</li>
     * </ul>
     *
     * @param agentId   原始分配目标 Agent ID
     * @param subTaskId 待分配的子任务 ID
     * @param t         原始异常（CallNotPermittedException 或 BizException）
     */
    @SuppressWarnings("unused")
    private void assignNextFallback(Long agentId, Long subTaskId, Throwable t) {
        doAssignNextFallback(agentId, subTaskId, null, t);
    }

    /** 带任务级约束的熔断降级（替代选人同样受约束，见 {@link #assignNext(Long, Long, AgentSelectionConstraints)}）。 */
    @SuppressWarnings("unused")
    private void assignNextFallbackWithConstraints(Long agentId, Long subTaskId,
                                                   AgentSelectionConstraints constraints, Throwable t) {
        doAssignNextFallback(agentId, subTaskId, constraints, t);
    }

    private void doAssignNextFallback(Long agentId, Long subTaskId,
                                      AgentSelectionConstraints constraints, Throwable t) {
        log.warn("调度降级触发: agentId={}, subTaskId={}, reason={}",
                agentId, subTaskId, t.getMessage());

        // 获取原 Agent 角色用于同角色替代
        Agent originalAgent = agentService.getById(agentId);
        AgentRole role = originalAgent != null ? originalAgent.getRole() : null;

        // 选取替代 Agent（任务级约束贯穿 fallback，选人不越出白名单/技能范围）
        Agent alternative = agentSelector.pickAlternative(agentId, role, constraints);

        if (alternative == null) {
            String msg = String.format(
                    "无可用替代 Agent: excludeAgentId=%d, role=%s", agentId, role);
            log.error(msg);
            throw new BizException(msg);
        }

        // 替代 Agent 同样必须通过执行密集能力预检。
        // 不匹配时已标记人工介入，任务保持 PENDING 由人工处置，不再抛异常冒泡。
        if (isExecutionDenseMismatch(alternative.getId(), subTaskId, alternative)) {
            log.warn("熔断降级替代 Agent 也无本机执行能力，放弃分配: subTaskId={}, alternativeAgentId={}",
                    subTaskId, alternative.getId());
            return;
        }

        log.info("熔断降级成功: originalAgentId={} → alternativeAgentId={}, subTaskId={}",
                agentId, alternative.getId(), subTaskId);
        subTaskService.assignNext(alternative.getId(), subTaskId);
    }
}
