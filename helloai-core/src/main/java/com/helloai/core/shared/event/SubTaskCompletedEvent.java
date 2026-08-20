package com.helloai.core.shared.event;

import lombok.Getter;

/**
 * 子任务已完成事件（内循环闭环收尾）。
 *
 * <p>{@code SubTaskService.complete}（REVIEW→DONE）提交后发布，
 * 事务提交后异步触发 {@code SubTaskCompletionListener}：
 * ①解锁同 Task 下依赖本子任务的下游 PENDING 节点；
 * ②全部子任务收尾后自动推进 Task→DONE（与 {@link SubTaskAssignedEvent} 模式一致）。</p>
 */
@Getter
public class SubTaskCompletedEvent {

    private final Long subTaskId;
    private final Long taskId;

    public SubTaskCompletedEvent(Long subTaskId, Long taskId) {
        this.subTaskId = subTaskId;
        this.taskId = taskId;
    }
}
