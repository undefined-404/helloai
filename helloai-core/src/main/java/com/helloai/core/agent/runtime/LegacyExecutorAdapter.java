package com.helloai.core.agent.runtime;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.service.SubTaskExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 旧 Executor 适配器（Phase 0 C2，双轨接线）。
 *
 * <p>以 {@link AgentRuntime} 契约包装旧执行链：委托 {@code SubTaskExecutionService.executeCommand}
 * （完整编排入口：参数校验 → startIfNeeded 状态推进 → executeOnce 执行 → ExecutionResultHandler 回写），
 * 一次 execute 即一次完整「分配后执行 → 结果回写」闭环。</p>
 *
 * <p>双轨期间旧路径保持功能不变（执行方案假设 4）：本适配器<b>不重复发 B2 事件</b>
 * （AGENT_STARTED / CONTEXT_BUILT / TOOL_CALL_STARTED / TOOL_CALL_COMPLETED / AGENT_COMPLETED
 * 已由旧链 executeOnce / ExecutionResultHandler 埋点发出），只做契约翻译。
 * 旧链结果无 TIMEOUT 区分（boolean success），TIMEOUT 终态留给新 Runtime。</p>
 *
 * <p>装配开关（项目惯例：条件装配直标 Bean 类，见 WatchdogLeaseRenewTask / WebSearch 多实现
 * 等 16 处先例）：{@code helloai.agent.runtime.v2-enabled=true} 时注册本适配器；
 * 默认关闭，旧消费链（MQ / DB Poller / 调度器）不受影响。</p>
 *
 * @see AgentRuntime
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "helloai.agent.runtime", name = "v2-enabled",
        havingValue = "true", matchIfMissing = false)
public class LegacyExecutorAdapter implements AgentRuntime {

    /** 命令触发来源标识：区分 Runtime 契约路径与旧消费者路径（仅落库 / 事件 payload，无分支逻辑）。 */
    static final String TRIGGER_AGENT_RUNTIME = "agent_runtime";

    private final SubTaskExecutionService subTaskExecutionService;

    @Override
    public AgentExecutionResult execute(AgentContext ctx) {
        if (ctx == null || ctx.getSubTaskId() == null || ctx.getAgentId() == null) {
            log.warn("LegacyExecutorAdapter 入参缺失，跳过执行: subTaskId={}, agentId={}",
                    ctx == null ? null : ctx.getSubTaskId(), ctx == null ? null : ctx.getAgentId());
            return fail("subTaskId / agentId 不可为空");
        }

        ExecutionCommand command = ExecutionCommand.builder()
                .subTaskId(ctx.getSubTaskId())
                .agentId(ctx.getAgentId())
                .trigger(TRIGGER_AGENT_RUNTIME)
                .build();

        try {
            return toResult(subTaskExecutionService.executeCommand(command));
        } catch (BizException e) {
            // 业务校验失败（子任务不存在 / 未分配 / Agent 不匹配等）：契约化返回，不向上抛
            log.warn("LegacyExecutorAdapter 业务校验失败: runId={}, subTaskId={}, agentId={}, err={}",
                    ctx.getRunId(), ctx.getSubTaskId(), ctx.getAgentId(), e.getMessage());
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("LegacyExecutorAdapter 执行异常: runId={}, subTaskId={}, agentId={}",
                    ctx.getRunId(), ctx.getSubTaskId(), ctx.getAgentId(), e);
            return fail(e.getMessage());
        }
    }

    private AgentExecutionResult toResult(AgentResult result) {
        // 旧链约定：成功信息在 output、失败信息在 errorMessage（AgentResult.success/failure 工厂）；
        // 新契约统一以 output 承载正文，失败时取 errorMessage。
        return AgentExecutionResult.builder()
                .status(result.isSuccess() ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED)
                .output(result.isSuccess() ? result.getOutput() : result.getErrorMessage())
                .build();
    }

    private AgentExecutionResult fail(String message) {
        return AgentExecutionResult.builder()
                .status(ExecutionStatus.FAILED)
                .output(message)
                .build();
    }
}