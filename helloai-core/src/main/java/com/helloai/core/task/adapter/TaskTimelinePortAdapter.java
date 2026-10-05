package com.helloai.core.task.adapter;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * {@link TaskTimelinePort} 的提供方（task 域）实现 —— 薄委托，不重写任何语义。
 *
 * <p>本类位于提供方 {@code task} 域，依赖消费方 {@code agent} 定义的端口，
 * 方向为 {@code task → agent}，属 CODE_STYLE §6 <b>顺向合法</b>。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskTimelinePortAdapter implements TaskTimelinePort {

    private final TaskTimelineService taskTimelineService;

    @Override
    public void recordEvent(Long taskId,
                            Long subTaskId,
                            String eventType,
                            AgentRole role,
                            Long agentId,
                            Map<String, Object> payload) {
        // 薄委托：不改写入时机（仍与调用方同事务）、不改 payload 结构
        taskTimelineService.recordEvent(taskId, subTaskId, eventType, role, agentId, payload);
    }
}
