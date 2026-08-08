package com.helloai.core.shared.doorbell;

import com.helloai.core.shared.event.InboxMessageCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 门铃响铃器（AgentHub V3 门铃响铃 PR-2）。
 *
 * <p><b>状态注记（2026-08-07）</b>：门铃通道已搁置。技术瓶颈——外部 AI Agent
 * （安装版 / CLI 版）均为单向执行器，无法处理平台推送的门铃信号，且 Agent 端代码
 * 不可修改；任务感知一律由 pullTasks 轮询承担。本代码保留运行，待未来 Agent 端
 * 常驻 daemon（官方插件 / CLI 包装器）落地后可复用本通道。</p>
 *
 * <p>监听 {@link InboxMessageCreatedEvent}，在收件箱记录所在事务<b>提交后</b>
 * 向目标 Agent 门铃响铃，把感知延迟从 30 秒轮询级降到秒级。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>AFTER_COMMIT</b>：先落库、后响铃，绝不在消息真正可见前就唤醒 Agent；</li>
 *   <li><b>@Async</b>：SSE 写出可能因客户端慢消费而阻塞，异步到专用 {@code doorbellExecutor}，
 *       不拖累提交事务的请求线程 / MQ 监听线程；</li>
 *   <li><b>尽力而为</b>：{@link DoorbellService#ring} 未连/失败一律静默降级，
 *       消息事实早已在 {@code agent_inbox}，Agent 始终可用 pullTasks 轮询兜底。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoorbellRinger {

    private final DoorbellService doorbellService;

    /**
     * 收件箱消息落库提交后响铃。
     *
     * <p>异常不外抛：响铃是尽力而为的旁路，任何失败都不应影响主链路，
     * 也不应触发事务补偿（此时事务已提交）。</p>
     */
    @Async("doorbellExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInboxMessageCreated(InboxMessageCreatedEvent event) {
        if (event == null || event.getAgentId() == null) {
            return;
        }
        try {
            boolean rung = doorbellService.ring(event.getAgentId(),
                    DoorbellSignal.inbox(event.getEventType(), event.getRefType(), event.getRefId()));
            if (rung) {
                log.debug("门铃已响: agentId={}, eventType={}, eventId={}",
                        event.getAgentId(), event.getEventType(), event.getEventId());
            }
            // 未响铃（Agent 未连门铃）属正常情况，静默由轮询兜底，不打日志避免噪音
        } catch (Exception e) {
            // 兜底：响铃旁路的任何异常都不外抛
            log.debug("门铃响铃异常，忽略（靠轮询兜底）: agentId={}, err={}",
                    event.getAgentId(), e.toString());
        }
    }
}
