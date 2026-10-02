package com.helloai.core.agent.mqconsumer;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.AgentRuntime;
import com.helloai.core.agent.runtime.ExecutionEnvironment;
import com.helloai.core.agent.runtime.ExecutionEnvironmentProvider;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentMcpServerService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.service.AgentRuntimeContextAssembler;
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
 * <p>G-002 单轨（2026-09-30）：旧链（executeCommand / executeOnce / TurnLlmCaller）与双轨路由
 * （RuntimeAgentRuntimeRouter / LegacyExecutorAdapter）已删除，本类为唯一执行入口，分层编排：</p>
 * <ol>
 *     <li>加载 subTask / agent，做一致性校验</li>
 *     <li>{@link SubTaskCommandPort#startIfNeeded} 幂等状态推进（ASSIGNED / REWORK / PAUSED → IN_PROGRESS）</li>
 *     <li>{@link AgentExecutionRecordService#markRunning} CAS 执行记录 PENDING→RUNNING</li>
 *     <li>记录消费阶段 timeline（route 观察点，route=agent_runtime）</li>
 *     <li>{@link AgentRuntimeContextAssembler#assemble} 装配真身上下文（prompt / chatModel / 会话）</li>
 *     <li>{@link AgentRuntime#execute} 真身执行（唯一实现 RuntimeTurnExecutor）+ {@link AgentRuntimeContextAssembler#afterTurn} 会话推进</li>
 *     <li>{@link AgentExecutionRecordService#markSuccess} / {@link AgentExecutionRecordService#markFailed} CAS 执行记录</li>
 *     <li>{@link ExecutionResultHandler#handleSuccess} / {@link ExecutionResultHandler#handleFailure} 结果回写</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalExecutionCommandConsumer implements ExecutionCommandConsumer {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelinePort taskTimelinePort;
    /** 子任务只读端口（原 {@code SubTaskService}；W11 端口化，读快照 {@link SubTaskSnapshot}）。 */
    private final SubTaskQueryPort subTaskQueryPort;
    private final AgentService agentService;
    /** Phase 1 Step 2：启用工具为 agent 域数据（agent_mcp_server），消费侧 agent 域内直读注入 ctx.tools。 */
    private final AgentMcpServerService agentMcpServerService;
    /** Phase 1 Step 4：执行环境为 agent 域数据（agent.accessType），消费侧 agent 域内解析注入 ctx.environment。 */
    private final ExecutionEnvironmentProvider executionEnvironmentProvider;
    /** G-002 单轨：状态推进（agent 域端口的不透明命令入口，判定在 task 域）。 */
    private final SubTaskCommandPort subTaskCommandPort;
    /** G-002 单轨：Runtime 真身上下文装配（prompt / chatModel / 会话；事件骨架归真身防双写）。 */
    private final AgentRuntimeContextAssembler contextAssembler;
    /** G-002 单轨：结果回写（成功 submit → REVIEW / 失败 block，含 Task Running Spec 回填与物化）。 */
    private final ExecutionResultHandler executionResultHandler;

    /**
     * Runtime 实现列表（G-002 单轨）：唯一实现为 {@code RuntimeTurnExecutor}（Router / Legacy
     * 适配器已删除）；列表为空仅发生在装配异常，防御跳过。
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

        // 1. 加载 subTask + agent，做一致性校验（快照只读，零 task 实体泄漏）
        SubTaskSnapshot subTask = subTaskQueryPort.findById(command.getSubTaskId());
        if (subTask == null) {
            log.warn("执行命令消费跳过：subTask 不存在 subTaskId={}", command.getSubTaskId());
            return;
        }
        if (subTask.assignedAgentId() == null) {
            log.warn("执行命令消费跳过：subTask 未分配 Agent subTaskId={}", command.getSubTaskId());
            return;
        }
        if (!command.getAgentId().equals(subTask.assignedAgentId())) {
            log.warn("执行命令消费跳过：command.agentId={} 与 subTask.assignedAgent={} 不匹配",
                    command.getAgentId(), subTask.assignedAgentId());
            return;
        }
        Agent agent = agentService.getById(subTask.assignedAgentId());
        if (agent == null) {
            log.warn("执行命令消费跳过：Agent 不存在 agentId={}", subTask.assignedAgentId());
            return;
        }

        // G-002 单轨：消费统一经 Runtime 真身（唯一 AgentRuntime 实现 RuntimeTurnExecutor）。
        // 一致性校验已通过；状态推进 / 装配 / 执行 / 回写在本层分层编排（见 runViaRuntime）。
        if (agentRuntimes == null || agentRuntimes.isEmpty()) {
            log.error("无 AgentRuntime 实现，执行被跳过（单轨下理论不可达）: subTaskId={}",
                    command.getSubTaskId());
            return;
        }
        runViaRuntime(command, subTask, agent);
    }

    /**
     * Runtime 真身路径执行（G-002 单轨后唯一执行入口，分层编排）：
     * startIfNeeded 状态推进 → record CAS → 消费 timeline → 上下文装配 →
     * 真身 execute + afterTurn 会话推进 → record CAS 终态 → ExecutionResultHandler 回写。
     */
    private void runViaRuntime(ExecutionCommand command, SubTaskSnapshot subTask, Agent agent) {
        // 1. 状态推进（幂等：IN_PROGRESS 恒过；ASSIGNED / REWORK / PAUSED → IN_PROGRESS）。
        //    失败契约化：record CAS 终态 + 回写失败，不进入执行阶段
        try {
            subTaskCommandPort.startIfNeeded(command.getSubTaskId(), subTask.status());
        } catch (Exception e) {
            log.error("子任务状态推进失败: subTaskId={}, agentId={}, err={}",
                    command.getSubTaskId(), command.getAgentId(), e.getMessage());
            if (command.getRecordId() != null) {
                agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage());
            }
            executionResultHandler.handleFailure(command.getSubTaskId(), command.getAgentId(), e);
            return;
        }

        // 2. 执行记录 CAS：PENDING → RUNNING（双入口并发时由 CAS 保证幂等，与旧直连同语义）
        if (command.getRecordId() != null && !agentExecutionRecordService.markRunning(command.getRecordId())) {
            log.warn("跳过执行(记录已非 PENDING, route=agent_runtime): recordId={}", command.getRecordId());
            return;
        }

        // 3. 消费阶段 timeline（route 观察点：灰度脚本据此区分路径）
        taskTimelinePort.recordEvent(
                subTask.taskId(),
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
        taskTimelinePort.recordEvent(
                subTask.taskId(),
                command.getSubTaskId(),
                "sub_task_execute_start",
                AgentRole.EXECUTOR,
                command.getAgentId(),
                Map.of("executor", "agent_runtime"));

        // 4. 装配 + 真身执行 + 会话推进（事件骨架由真身内部记录，防双写见 AgentRuntimeContextAssembler）
        AgentExecutionResult result;
        try {
            // Phase 1 Step 1 fix（LOG-20260904-009）：requiredSkills 由命令装箱传入，
            // 本层不再反向查询 task（§6 依赖方向红线）；command.requiredSkills 恒非 null，
            // 这里仅保留防御
            List<String> skills = command.getRequiredSkills() != null
                    ? command.getRequiredSkills() : Collections.emptyList();
            // Phase 1 Step 2：启用工具为 agent 域数据（agent_mcp_server），消费侧 agent 域内
            // 直读注入 ctx.tools（与 skills 的 task 域装箱不同，无 §6 跨域问题）；恒非 null 仅防御
            List<String> tools = agentMcpServerService.getEnabledTools(command.getAgentId());
            if (tools == null) {
                tools = Collections.emptyList();
            }
            // Phase 1 Step 4：执行环境为 agent 域数据（agent.accessType 随命令透传），
            // 消费侧 agent 域内解析注入；accessType 为 null 或无命中时保持 null（Phase 0 语义兼容）
            ExecutionEnvironment environment = executionEnvironmentProvider.resolve(command.getAccessType());
            AgentContext ctx = contextAssembler.assemble(command, subTask, agent, tools, environment);
            result = agentRuntimes.get(0).execute(ctx);
            contextAssembler.afterTurn(subTask, agent, ctx.getTurn(), result);
        } catch (Exception e) {
            // 装配 / 真身违约异常契约化：record CAS 终态 + 回写失败（与旧链 executeCommand catch 等价）
            log.error("Runtime 真身执行异常: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId(), e);
            if (command.getRecordId() != null) {
                agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage());
            }
            executionResultHandler.handleFailure(command.getSubTaskId(), command.getAgentId(), e);
            return;
        }

        // 5. 执行记录 CAS：终态覆盖（SUCCESS → markSuccess；FAILED / TIMEOUT → markFailed）；
        // tokenUsage 随终态落库（B5：loop 全部轮次累加，provider 未返回时为 null）
        if (command.getRecordId() != null) {
            boolean marked = result.getStatus() == ExecutionStatus.SUCCESS
                    ? agentExecutionRecordService.markSuccess(command.getRecordId(), result.getTokenUsage())
                    : agentExecutionRecordService.markFailed(command.getRecordId(), result.getOutput(),
                            result.getTokenUsage());
            if (!marked) {
                log.warn("{} 写入被拒绝(记录已超时补偿): recordId={}", result.getStatus(), command.getRecordId());
            }
        }

        // 6. 结果回写（成功 → submit → REVIEW + 物化；失败 → block + 失败轨迹）
        if (result.getStatus() == ExecutionStatus.SUCCESS) {
            executionResultHandler.handleSuccess(command.getSubTaskId(), command.getAgentId(), toAgentResult(result));
        } else {
            executionResultHandler.handleFailure(command.getSubTaskId(), command.getAgentId(),
                    new BizException(result.getOutput() != null ? result.getOutput() : "agent_runtime_failed"));
        }
        log.info("执行命令消费成功(route=agent_runtime): subTaskId={}, agentId={}, recordId={}, status={}",
                command.getSubTaskId(), command.getAgentId(), command.getRecordId(), result.getStatus());
    }

    /** Runtime 真身结果 → 旧 AgentResult 契约映射（成功带正文/思考/结束原因；失败带错误正文）。 */
    private AgentResult toAgentResult(AgentExecutionResult result) {
        if (result.getStatus() == ExecutionStatus.SUCCESS) {
            return AgentResult.success(result.getOutput(), result.getThinking(), result.getFinishReason(),
                    "RuntimeTurnExecutor", result.getTokenUsage());
        }
        return AgentResult.failure(result.getOutput() != null ? result.getOutput() : "agent_runtime_failed",
                result.getFinishReason(), "RuntimeTurnExecutor");
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
