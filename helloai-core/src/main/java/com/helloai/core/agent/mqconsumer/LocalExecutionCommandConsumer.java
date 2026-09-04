package com.helloai.core.agent.mqconsumer;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.AgentRuntime;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.shared.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台内执行命令消费者。
 *
 * <p>{@link #consume(ExecutionCommand)} 是与命令来源无关的实际消费入口，供 DB Poller 直接调用；
 * {@link #onCommandCreated(ExecutionCommandCreatedEvent)} 仅作为 EVENT / BOTH 模式的本地事务事件适配器。
 * POLLER 模式下 {@code ExecutionCommandService} 不发布事件，因此不会进入事件适配器，
 * 但本 Bean 必须保留，供 Poller 完成实际消费。</p>
 *
 * <p>Phase 0 C3 Step 5/6（下线旧直连 + 清理路由分支，LOG-20260904-006）：执行统一收敛到
 * {@link AgentRuntime} 接口（唯一执行契约），旧直连分层链（startIfNeeded / executeOnce /
 * ExecutionResultHandler 直调）已下线，消费侧职责为：</p>
 * <ol>
 *     <li>加载 subTask / agent，做一致性校验</li>
 *     <li>{@link AgentExecutionRecordService#markRunning} CAS 执行记录 PENDING→RUNNING</li>
 *     <li>记录消费阶段 timeline（route 观察点，route=agent_runtime）</li>
 *     <li>{@link AgentRuntime#execute}（内部完成状态推进 / 纯执行 / 结果回写，见 LegacyExecutorAdapter）</li>
 *     <li>{@link AgentExecutionRecordService#markSuccess} / {@link AgentExecutionRecordService#markFailed} CAS 执行记录</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalExecutionCommandConsumer implements ExecutionCommandConsumer {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskService subTaskService;
    private final AgentService agentService;
    /** Phase 1 T1：契约供电所需 TaskService（agent 域 → task 域接口依赖，先例同 SubTaskService/TaskTimelineService）。 */
    private final TaskService taskService;

    /**
     * Runtime 实现列表（Phase 0 C3 双轨）：v2-enabled 已固化 true（Step 6），
     * LegacyExecutorAdapter 恒注册；列表为空仅发生在装配异常，防御跳过。
     */
    private final List<AgentRuntime> agentRuntimes;

    @Async("executionCommandExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCreated(ExecutionCommandCreatedEvent event) {
        consume(event.getCommand());
    }

    @Override
    public void consume(ExecutionCommand command) {
        if (command == null) {
            log.warn("执行命令消费跳过：command 为空");
            return;
        }
        if (command.getSubTaskId() == null) {
            log.warn("执行命令消费跳过：subTaskId 为空");
            return;
        }
        if (command.getAgentId() == null) {
            log.warn("执行命令消费跳过：agentId 为空");
            return;
        }

        // 1. 加载 subTask + agent，做一致性校验
        SubTask subTask = subTaskService.getById(command.getSubTaskId());
        if (subTask == null) {
            log.warn("执行命令消费跳过：subTask 不存在 subTaskId={}", command.getSubTaskId());
            return;
        }
        if (subTask.getAssignedAgentId() == null) {
            log.warn("执行命令消费跳过：subTask 未分配 Agent subTaskId={}", command.getSubTaskId());
            return;
        }
        if (!command.getAgentId().equals(subTask.getAssignedAgentId())) {
            log.warn("执行命令消费跳过：command.agentId={} 与 subTask.assignedAgent={} 不匹配",
                    command.getAgentId(), subTask.getAssignedAgentId());
            return;
        }
        Agent agent = agentService.getById(subTask.getAssignedAgentId());
        if (agent == null) {
            log.warn("执行命令消费跳过：Agent 不存在 agentId={}", subTask.getAssignedAgentId());
            return;
        }

        // Phase 0 C3 Step 5/6：旧直连执行链已下线，消费统一经 AgentRuntime（唯一执行契约）。
        // 一致性校验已通过；record CAS 与消费 timeline 在本层补齐，
        // 状态推进 / 纯执行 / 结果回写由 Runtime 实现内部完成（LegacyExecutorAdapter.executeCommand）。
        if (agentRuntimes == null || agentRuntimes.isEmpty()) {
            log.error("无 AgentRuntime 实现，执行被跳过（v2-enabled 已固化 true，理论不可达）: subTaskId={}",
                    command.getSubTaskId());
            return;
        }
        runViaRuntime(command, subTask);
    }

    /**
     * Runtime 路径执行（Phase 0 C3 Step 5 后唯一执行入口）：只补消费侧职责——
     * record CAS（markRunning → markSuccess / markFailed）与消费 timeline（route 观察点），
     * 状态推进 / 纯执行 / 结果回写由 {@link AgentRuntime} 实现内部完成。
     */
    private void runViaRuntime(ExecutionCommand command, SubTask subTask) {
        // 1. 执行记录 CAS：PENDING → RUNNING（双入口并发时由 CAS 保证幂等，与旧直连同语义）
        if (command.getRecordId() != null && !agentExecutionRecordService.markRunning(command.getRecordId())) {
            log.warn("跳过执行(记录已非 PENDING, route=agent_runtime): recordId={}", command.getRecordId());
            return;
        }

        // 2. 消费阶段 timeline（route 观察点：灰度脚本据此区分路径）
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                command.getSubTaskId(),
                "sub_task_execution_command_consume",
                AgentRole.EXECUTOR,
                command.getAgentId(),
                safeMap(
                        "trigger", command.getTrigger(),
                        "recordId", command.getRecordId(),
                        "eventId", command.getEventId(),
                        "accessType", command.getAccessType() != null ? command.getAccessType().name() : "UNKNOWN",
                        "route", "agent_runtime"));
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                command.getSubTaskId(),
                "sub_task_execute_start",
                AgentRole.EXECUTOR,
                command.getAgentId(),
                Map.of("executor", "agent_runtime"));

        // 3. 构造 Runtime 上下文并执行（run_id / turn 与 B2 埋点同源，见 AgentEventContextResolver）
        AgentExecutionResult result;
        try {
            // Phase 1 T1：契约供电——按主任务 requiredSkills 注入 AgentContext.skills（与 AgentSelector.requiredSkills 同表示）
            // task 缺失/requiredSkills 为 null 防御：与现有 consume 防御风格一致，不阻断执行
            Task task = taskService.getById(subTask.getTaskId());
            List<String> skills = (task != null && task.getRequiredSkills() != null)
                    ? task.getRequiredSkills() : Collections.emptyList();
            result = agentRuntimes.get(0).execute(AgentContext.builder()
                    .runId(AgentEventContextResolver.resolveRunId(subTask.getTaskId()))
                    .taskId(subTask.getTaskId())
                    .subTaskId(command.getSubTaskId())
                    .turn(AgentEventContextResolver.resolveTurn(subTask))
                    .step(0)
                    .agentId(command.getAgentId())
                    .skills(skills)
                    .build());
        } catch (Exception e) {
            // 契约承诺失败以 status 表达不抛异常；此处防御未来 Runtime 实现的违约实现
            log.error("Runtime 执行异常: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId(), e);
            if (command.getRecordId() != null) {
                agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage());
            }
            return;
        }

        // 4. 执行记录 CAS：终态覆盖（SUCCESS → markSuccess；FAILED / TIMEOUT → markFailed）
        if (command.getRecordId() != null) {
            boolean marked = result.getStatus() == ExecutionStatus.SUCCESS
                    ? agentExecutionRecordService.markSuccess(command.getRecordId())
                    : agentExecutionRecordService.markFailed(command.getRecordId(), result.getOutput());
            if (!marked) {
                log.warn("{} 写入被拒绝(记录已超时补偿): recordId={}", result.getStatus(), command.getRecordId());
            }
        }
        log.info("执行命令消费成功(route=agent_runtime): subTaskId={}, agentId={}, recordId={}, status={}",
                command.getSubTaskId(), command.getAgentId(), command.getRecordId(), result.getStatus());
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }
}
