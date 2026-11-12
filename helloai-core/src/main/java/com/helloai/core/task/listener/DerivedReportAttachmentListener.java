package com.helloai.core.task.listener;

import com.helloai.core.shared.event.TaskFinalReportGeneratedEvent;
import com.helloai.core.task.service.DerivedReportAttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 派生任务报告回挂（`ADR-008` §15）。
 *
 * <p>只在报告写回事务<b>提交后</b>执行：报告生成链路本身可能发生 rework / 回滚，
 * 提交前挂附件会挂上未落库的旧稿。失败只告警——回挂是下游可读性的增强，
 * 不能反过来把「报告已生成」这个事实判成失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DerivedReportAttachmentListener {

    private final DerivedReportAttachmentService derivedReportAttachmentService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinalReportGenerated(TaskFinalReportGeneratedEvent event) {
        try {
            derivedReportAttachmentService.attachIfDue(event.getTaskId());
        } catch (Exception e) {
            log.warn("派生报告回挂失败（不影响报告生成与任务完成结论）: derivedTaskId={}, err={}",
                    event.getTaskId(), e.getMessage());
        }
    }
}
