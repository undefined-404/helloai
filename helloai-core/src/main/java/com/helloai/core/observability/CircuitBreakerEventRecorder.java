package com.helloai.core.observability;

import com.helloai.common.constant.AgentRole;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.helloai.core.service.TaskTimelineService;

/**
 * 熔断器事件审计记录器（v2.4 §4.7）。
 *
 * <p>监听 per-agent 熔断器状态变更，写入 DB task_timeline 表。
 * 通过 {@link #registerListener} 为每个 Agent 的独立熔断器注册回调，
 * 状态从 CLOSED→OPEN 写 circuit_open，从 OPEN→HALF_OPEN 写 circuit_half_open，
 * 从 HALF_OPEN→CLOSED 写 circuit_close。</p>
 *
 * <p>使用 ConcurrentHashMap 追踪已注册的熔断器名，避免重复注册。</p>
 *
 * @see ResilientDispatcher
 * @see TaskTimelineService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventRecorder {

    private final TaskTimelineService taskTimelineService;
    private final CircuitBreakerAlertService alertService;
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    /**
     * 为指定 Agent 的熔断器注册状态变更监听。
     *
     * <p>幂等：同一 cbName 多次调用只注册一次。</p>
     *
     * @param agentId       关联的 Agent ID
     * @param circuitBreaker per-agent 熔断器实例
     */
    public void registerListener(Long agentId, CircuitBreaker circuitBreaker) {
        String cbName = circuitBreaker.getName();
        if (!registered.add(cbName)) {
            return; // 已注册
        }

        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            recordStateTransition(agentId, cbName, event);
        });

        log.info("熔断器事件监听已注册: cbName={}, agentId={}", cbName, agentId);
    }

    private void recordStateTransition(Long agentId, String cbName,
                                       CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.StateTransition transition = event.getStateTransition();
        String fromState = transition.getFromState().name();
        String toState = transition.getToState().name();

        String eventType = switch (toState) {
            case "OPEN" -> "circuit_open";
            case "HALF_OPEN" -> "circuit_half_open";
            case "CLOSED" -> "circuit_close";
            case "FORCED_OPEN" -> "circuit_forced_open";
            case "DISABLED" -> "circuit_disabled";
            default -> "circuit_state_change";
        };

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cbName", cbName);
        payload.put("fromState", fromState);
        payload.put("toState", toState);
        payload.put("at", OffsetDateTime.now().toString());

        try {
            taskTimelineService.recordEvent(
                    null,           // 系统级事件，无主任务
                    null,           // 系统级事件，无子任务
                    eventType,
                    AgentRole.SYSTEM,
                    agentId,
                    payload);
        } catch (Exception e) {
            log.error("熔断事件审计写入失败: cbName={}, eventType={}", cbName, eventType, e);
        }

        log.warn("熔断器状态变更: cbName={}, {} → {}, agentId={}",
                cbName, fromState, toState, agentId);

        // 异步发送 Webhook 报警
        alertService.onCircuitStateChange(cbName, agentId, fromState, toState);
    }
}
