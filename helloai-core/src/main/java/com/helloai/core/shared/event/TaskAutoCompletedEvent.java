package com.helloai.core.shared.event;

import lombok.Getter;

/**
 * 任务自动收口完成事件（V32 最终整合报告触发源）。
 *
 * <p>{@code SubTaskCompletionListener.tryCloseTask} 用 CAS 把 Task 推 DONE
 * 成功（updated &gt; 0，赢家唯一）后发布；此时已在 AFTER_COMMIT 异步线程、
 * <b>无事务上下文</b>，消费方必须用普通 {@code @EventListener}（而非
 * {@code @TransactionalEventListener}，否则收不到）+ {@code @Async} 承接。</p>
 */
@Getter
public class TaskAutoCompletedEvent {

    private final Long taskId;

    public TaskAutoCompletedEvent(Long taskId) {
        this.taskId = taskId;
    }
}
