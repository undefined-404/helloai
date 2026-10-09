package com.helloai.core.agent.dispatcher;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.shared.event.SubTaskAssignedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.service.AgentService;

/**
 * 子任务自动执行命令派发器。
 *
 * <p>当子任务进入 ASSIGNED 后，在事务提交后异步判断是否应生成执行命令。
 * 当前只对 {@link AgentAccessType#API_KEY_LLM} 创建 execution command；
 * CLI_CLIENT 仍走收件箱/MCP 拉取链路。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskAutoExecutionDispatcher {

    private final AgentService agentService;
    private final SubTaskQueryPort subTaskQueryPort;
    private final ExecutionCommandService executionCommandService;
    private final TaskTimelinePort taskTimelinePort;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssigned(SubTaskAssignedEvent event) {
        Agent agent = agentService.getById(event.getAgentId());
        if (agent == null) {
            log.warn("自动执行跳过：Agent 不存在, subTaskId={}, agentId={}",
                    event.getSubTaskId(), event.getAgentId());
            return;
        }
        if (agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            log.debug("自动执行跳过：accessType={}, subTaskId={}, agentId={}",
                    agent.getAccessType(), event.getSubTaskId(), event.getAgentId());
            return;
        }

        SubTaskSnapshot subTask = subTaskQueryPort.findById(event.getSubTaskId());
        if (subTask == null) {
            log.warn("自动执行跳过：子任务不存在, subTaskId={}", event.getSubTaskId());
            return;
        }

        taskTimelinePort.recordEvent(
                subTask.taskId(),
                subTask.id(),
                "sub_task_auto_execute_dispatch",
                AgentRole.SYSTEM,
                agent.getId(),
                Map.of("trigger", "assigned", "accessType", agent.getAccessType().name()));

        try {
            // LOG-20260904-009：requiredSkills 装箱透传
            // （task 域数据随命令正向传入执行侧，执行侧不再反向查询 task）
            // G-010：改用并集装箱（子任务级 ∪ 任务级），与审查核验同源
            executionCommandService.createAssignedCommand(event.getSubTaskId(), agent.getId(), "assigned",
                    subTaskQueryPort.mergeSkills(event.getSubTaskId()));
            log.info("执行命令派发成功: subTaskId={}, agentId={}", event.getSubTaskId(), agent.getId());
        } catch (Exception e) {
            log.error("执行命令派发失败: subTaskId={}, agentId={}", event.getSubTaskId(), agent.getId(), e);
        }
    }
}
