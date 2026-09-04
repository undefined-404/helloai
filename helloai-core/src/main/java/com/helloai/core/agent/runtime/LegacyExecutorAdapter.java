package com.helloai.core.agent.runtime;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.service.SubTaskExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 旧 Executor 适配器（Phase 0 C2，双轨接线；Step 6 后恒注册）。
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
 * <p>装配（Phase 0 C3 Step 6，LOG-20260904-006）：v2-enabled 已固化 true，本适配器恒注册——
 * {@code LocalExecutionCommandConsumer} 依赖它作为唯一执行契约；下线后回退需代码回滚
 * （预研回滚表格「下线后」行）。</p>
 *
 * @see AgentRuntime
 */
@Slf4j
@Component
@RequiredArgsConstructor
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

        // Phase 1 Step 1 fix（LOG-20260904-009）：requiredSkills 由上下文透传
        // （消费侧已从命令装箱，ctx.skills 恒非 null 由 AgentContext 保证）
        ExecutionCommand command = ExecutionCommand.builder()
                .subTaskId(ctx.getSubTaskId())
                .agentId(ctx.getAgentId())
                .trigger(TRIGGER_AGENT_RUNTIME)
                .requiredSkills(ctx.getSkills())
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