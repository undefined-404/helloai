package com.helloai.core.agent.dispatcher;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
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

    @Async("appEventExecutor")
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

        try {
            // 派单事件亦纳入 try：`HIGH-1` 修补时发现它原先在 try **之外** ——
            // 若时间线写失败（DB 抖动）会直接中断派发，且异常落进 @Async 线程的
            // 未捕获处理器（更难定位）。写事件属可观测面，不得挡住派发本身。
            taskTimelinePort.recordEvent(
                    subTask.taskId(),
                    subTask.id(),
                    TimelineEventCode.SUB_TASK_AUTO_EXECUTE_DISPATCH.code(),
                    AgentRole.SYSTEM,
                    agent.getId(),
                    Map.of("trigger", "assigned", "accessType", agent.getAccessType().name()));

            // LOG-20260904-009：requiredSkills 装箱透传
            // （task 域数据随命令正向传入执行侧，执行侧不再反向查询 task）
            // G-010：改用并集装箱（子任务级 ∪ 任务级），与审查核验同源
            executionCommandService.createAssignedCommand(event.getSubTaskId(), agent.getId(), "assigned",
                    subTaskQueryPort.mergeSkills(event.getSubTaskId()));
            log.info("执行命令派发成功: subTaskId={}, agentId={}", event.getSubTaskId(), agent.getId());
        } catch (Exception e) {
            // HIGH-1（2026-10-15 修复）：原实现只 log.error ⇒ 派发失败在操作面**不可见**
            //（日志易被淹没），子任务停在 ASSIGNED 且无 agent_execution_record。
            // 现补落时间线事件，使「派发失败 → 10 分钟后由 AssignedSubTaskTimeoutTask 回收重派」
            // 这条自愈链在用户可见面上有前因，而不是只有结果。
            // 复用既有事件码 SUB_TASK_AUTO_EXECUTE_DISPATCH_FAIL（无需新增事件码，
            // 避免事件字典 / 前端文案联动）；异常本身仍不冒泡（@Async 线程内抛错只会进
            // 线程池的 uncaught handler，反而更难定位）。
            log.error("执行命令派发失败: subTaskId={}, agentId={}", event.getSubTaskId(), agent.getId(), e);
            recordDispatchFailure(subTask, agent, e);
        }
    }

    /**
     * 派发失败时间线留痕（best-effort，失败仅告警不阻断）。
     *
     * <p>与 {@link #onAssigned} 主链解耦：本方法自身异常不得影响派发异常的处理路径。</p>
     */
    private void recordDispatchFailure(SubTaskSnapshot subTask, Agent agent, Exception cause) {
        try {
            taskTimelinePort.recordEvent(
                    subTask.taskId(),
                    subTask.id(),
                    TimelineEventCode.SUB_TASK_AUTO_EXECUTE_DISPATCH_FAIL.code(),
                    AgentRole.SYSTEM,
                    agent.getId(),
                    Map.of(
                            "trigger", "assigned",
                            "accessType", agent.getAccessType() != null ? agent.getAccessType().name() : "UNKNOWN",
                            "reason", cause.getClass().getSimpleName(),
                            "message", cause.getMessage() != null ? cause.getMessage() : "",
                            "recoveryHint", "assigned_timeout_reassign"));
        } catch (Exception te) {
            log.warn("派发失败时间线记录失败（不阻断）: subTaskId={}, err={}",
                    subTask != null ? subTask.id() : null, te.getMessage());
        }
    }
}
