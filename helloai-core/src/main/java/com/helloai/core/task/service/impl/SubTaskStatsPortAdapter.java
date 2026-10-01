package com.helloai.core.task.service.impl;

import com.helloai.core.agent.port.SubTaskStatsPort;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * {@link SubTaskStatsPort} 的提供方实现（task 域）。
 *
 * <p>纯薄委托：逐方法转发到 {@link SubTaskService}，<b>不改动任何 SQL、参数顺序与语义</b>
 * （委托层不做转换、不吞异常）。实现侧依赖 {@code task → agent.port} 属顺向合法。</p>
 */
@Service
@RequiredArgsConstructor
public class SubTaskStatsPortAdapter implements SubTaskStatsPort {

    private final SubTaskService subTaskService;

    @Override
    public Map<String, Integer> countByStatusForAgent(Long agentId) {
        return subTaskService.countByStatusForAgent(agentId);
    }

    @Override
    public long countByAssignedAgent(Long agentId) {
        return subTaskService.countByAssignedAgent(agentId);
    }

    @Override
    public long countReviewByReviewerAgent(Long agentId) {
        return subTaskService.countReviewByReviewerAgent(agentId);
    }

    @Override
    public int countInFlightByAgent(Long agentId) {
        return subTaskService.countInFlightByAgent(agentId);
    }

    @Override
    public boolean existsInFlight(Long agentId) {
        // 与原子调用方原语义完全一致：复用 selectInFlightByAgent(agentId, 1) 的在跑判定，
        // 仅把「取 List 后判空」收敛到提供方，避免 agent 域持有 task 实体。
        List<SubTask> inFlight = subTaskService.selectInFlightByAgent(agentId, 1);
        return inFlight != null && !inFlight.isEmpty();
    }
}
