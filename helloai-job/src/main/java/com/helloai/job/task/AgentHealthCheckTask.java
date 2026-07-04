package com.helloai.job.task;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHealthCheckTask {

    private final AgentService agentService;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:AgentHealth";

    @Scheduled(fixedRate = 60000)
    public void checkHealth() {
        if (!tryLock()) return;

        try {
            List<Agent> activeAgents = agentService.listActive();
            long totalActive = activeAgents.size();

            long executorCount = activeAgents.stream().filter(a -> a.getRole() == AgentRole.EXECUTOR).count();
            long reviewerCount = activeAgents.stream().filter(a -> a.getRole() == AgentRole.REVIEWER).count();
            long plannerCount = activeAgents.stream().filter(a -> a.getRole() == AgentRole.PLANNER).count();
            long patrolCount = activeAgents.stream().filter(a -> a.getRole() == AgentRole.PATROL).count();

            log.info("Agent 健康检查: total={}, planner={}, executor={}, reviewer={}, patrol={}",
                    totalActive, plannerCount, executorCount, reviewerCount, patrolCount);

            if (executorCount == 0) {
                log.warn("无活跃的 EXECUTOR，新任务无法执行");
            }
            if (reviewerCount == 0) {
                log.warn("无活跃的 REVIEWER，已完成的任务无法被审查");
            }

            // v1.1: 检测超过 30 分钟未活动的 Agent
            java.time.OffsetDateTime threshold30min = java.time.OffsetDateTime.now().minusMinutes(30);
            for (Agent agent : activeAgents) {
                if (agent.getUpdateTime() != null && agent.getUpdateTime().isBefore(threshold30min)) {
                    log.warn("Agent 可能离线: id={}, name={}, role={}, lastActive={}",
                            agent.getId(), agent.getName(), agent.getRole(), agent.getUpdateTime());
                }
            }

        } finally {
            unlock();
        }
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 30, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }
}
