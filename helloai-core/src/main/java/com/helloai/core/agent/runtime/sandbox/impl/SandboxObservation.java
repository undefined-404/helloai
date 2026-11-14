package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱观测（`SL-9`，2026-11-13）：调用台账 + 时间线事件的唯一写入口。
 *
 * <p><b>为什么独立成一个协作者而不是装饰器</b>：若在运行时外面套一层装饰器，装饰器必须先复制
 * 入口守卫（未启用 / 空命令<b>不得入账</b>），守卫一旦漂移，台账就会记下「不存在的执行」。
 * 现在守卫留在运行时入口、观测只做「写」，入账时机只有一个地方可改。</p>
 *
 * <p><b>只观测，不改结果</b>：台账或事件写失败一律降级告警，返回值照原样透传——
 * 观测故障不得改变执行结果；此时 {@code callId} 为 {@code null}，调用方按「无条目可引用」处理，
 * 不伪造 ID。</p>
 */
@Slf4j
@Component
public class SandboxObservation {

    private final SandboxCallLedger sandboxCallLedger;
    private final TaskTimelinePort taskTimelinePort;

    public SandboxObservation(SandboxCallLedger sandboxCallLedger, TaskTimelinePort taskTimelinePort) {
        this.sandboxCallLedger = sandboxCallLedger;
        this.taskTimelinePort = taskTimelinePort;
    }

    /** 缺省来源（执行者）/ 轮次 0 的入账重载。 */
    ExecuteResponse record(Long contextId, String command, ExecuteResponse response) {
        return record(contextId, command, response, SandboxCallOrigin.EXECUTOR, 0);
    }

    /**
     * 落调用台账 + timeline 观测事件，并把条目 ID 回填到结果（`SL-4`）。
     *
     * <p>台账与事件都以「实际尝试执行」为准：未启用 / 空命令两道入口守卫在此之前的运行时入口
     * 返回，不登记（那不是一次执行）。超时（124）与 SDK 异常（-1）同样是真实执行结果，照样入账。</p>
     *
     * @param contextId 观测归属 ID（子任务 / 会话；台账与时间线都用它，可 null）
     * @param command   实际执行的命令
     * @param response  执行结果
     * @param origin    调用来源（null ⇒ 执行者）
     * @param round     执行轮次（负数按 0）
     */
    ExecuteResponse record(Long contextId, String command, ExecuteResponse response,
                           SandboxCallOrigin origin, int round) {
        SandboxCallLedger.Entry entry = null;
        try {
            entry = sandboxCallLedger.record(contextId, command, response, origin, round);
        } catch (Exception e) {
            log.warn("沙箱调用台账写入失败（观测降级，不阻断执行）: contextId={}, err={}",
                    contextId, e.getMessage());
        }
        String callId = entry == null ? null : entry.callId();
        if (contextId != null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("command", SandboxCallLedger.commandPreview(command));
                payload.put("commandChars", command != null ? command.length() : 0);
                payload.put("exitCode", response.exitCode());
                payload.put("truncated", response.truncated());
                payload.put("digest", response.digest());
                payload.put("origin", entry != null ? entry.origin()
                        : (origin == null ? SandboxCallOrigin.EXECUTOR : origin).code());
                payload.put("round", entry != null ? entry.round() : Math.max(round, 0));
                if (callId != null) {
                    payload.put("callId", callId);
                }
                taskTimelinePort.recordEvent(null, contextId,
                        TimelineEventCode.SANDBOX_COMMAND_EXECUTED.code(), AgentRole.SYSTEM, null,
                        payload);
            } catch (Exception e) {
                log.warn("沙箱调用事件写入失败（观测降级，不阻断执行）: contextId={}, err={}",
                        contextId, e.getMessage());
            }
        }
        return response.withCallId(callId);
    }

    /**
     * 记一条「命令超时但工作区保留」的观测事件（`SL-7`）。
     *
     * <p>只做观测：事件写失败不影响命令结果（与 {@link #record} 同一条降级口径）。</p>
     *
     * @param contextId      观测归属 ID（null ⇒ 无归属可记，直接返回）
     * @param command        超时的命令
     * @param timeoutSeconds 本次生效的超时秒数
     */
    void timedOut(Long contextId, String command, long timeoutSeconds) {
        if (contextId == null) {
            return;
        }
        try {
            taskTimelinePort.recordEvent(null, contextId,
                    TimelineEventCode.SANDBOX_COMMAND_TIMED_OUT.code(), AgentRole.SYSTEM, null,
                    Map.of("command", SandboxCallLedger.commandPreview(command),
                            "timeoutSeconds", timeoutSeconds));
        } catch (Exception e) {
            log.warn("沙箱命令超时事件写入失败（观测降级，不阻断执行）: contextId={}, err={}",
                    contextId, e.getMessage());
        }
    }
}
