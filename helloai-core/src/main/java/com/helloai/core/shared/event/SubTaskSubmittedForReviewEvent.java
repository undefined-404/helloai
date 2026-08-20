package com.helloai.core.shared.event;

import lombok.Getter;

/**
 * 子任务已提交核验事件（内循环核验门控）。
 *
 * <p>执行成功回报 submit（→REVIEW）后由 {@code ExecutionResultHandler} 发布，
 * 事务提交后异步触发 {@code SubTaskReviewService} 的 LLM 自动核验，
 * 避免核验 LLM 调用阻塞结果回报事务（与 {@link SubTaskAssignedEvent} 模式一致）。</p>
 */
@Getter
public class SubTaskSubmittedForReviewEvent {

    private final Long subTaskId;
    /** 提交执行结果的 Agent（执行者），核验驳回后作为返工对象。 */
    private final Long executorAgentId;

    public SubTaskSubmittedForReviewEvent(Long subTaskId, Long executorAgentId) {
        this.subTaskId = subTaskId;
        this.executorAgentId = executorAgentId;
    }
}
