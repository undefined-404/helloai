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
            long totalActive = agentService.listActive().size();

            long executorCount = agentService.listByRole(AgentRole.EXECUTOR).stream()
                    .filter(a -> a.getStatus() == AgentStatus.ACTIVE).count();
            long reviewerCount = agentService.listByRole(AgentRole.REVIEWER).stream()
                    .filter(a -> a.getStatus() == AgentStatus.ACTIVE).count();
            long plannerCount = agentService.listByRole(AgentRole.PLANNER).stream()
                    .filter(a -> a.getStatus() == AgentStatus.ACTIVE).count();
            long patrolCount = agentService.listByRole(AgentRole.PATROL).stream()
                    .filter(a -> a.getStatus() == AgentStatus.ACTIVE).count();

            log.info("Agent 健康检查: total={}, planner={}, executor={}, reviewer={}, patrol={}",
                    totalActive, plannerCount, executorCount, reviewerCount, patrolCount);

            if (executorCount == 0) {
                log.warn("无活跃的 EXECUTOR，新任务无法执行");
            }
            if (reviewerCount == 0) {
                log.warn("无活跃的 REVIEWER，已完成的任务无法被审查");
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
