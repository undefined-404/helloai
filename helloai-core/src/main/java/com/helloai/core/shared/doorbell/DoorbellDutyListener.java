package com.helloai.core.shared.doorbell;

import com.helloai.core.shared.event.DutyLeaseClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 值班关闭 → 主动断门铃监听器（AgentHub 门铃 PR-3）。
 *
 * <p>监听 {@link DutyLeaseClosedEvent}，在值班租约关闭 / 到期的事务提交后主动断开
 * 对应 Agent 的门铃连接。语义："先打卡再接电话，签退 / 到期即挂电话"。</p>
 *
 * <p>与 {@link DoorbellRinger} 对称：都挂在领域事件的 {@code AFTER_COMMIT} 上、都跑在专用
 * {@code doorbellExecutor} 线程池、都尽力而为不回压主链路。断连是幂等的（未连门铃时 no-op），
 * 事务回滚则事件不投递、不会误断连。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DoorbellDutyListener {

    private final DoorbellService doorbellService;

    @Async("doorbellExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDutyLeaseClosed(DutyLeaseClosedEvent event) {
        if (event == null || event.getAgentId() == null) {
            return;
        }
        try {
            doorbellService.disconnect(event.getAgentId());
            log.debug("值班关闭，已断门铃: agentId={}, reason={}", event.getAgentId(), event.getReason());
        } catch (Exception e) {
            // 断门铃失败无副作用（Agent 已离岗），忽略即可
            log.debug("值班关闭断门铃异常，忽略: agentId={}, err={}", event.getAgentId(), e.toString());
        }
    }
}
