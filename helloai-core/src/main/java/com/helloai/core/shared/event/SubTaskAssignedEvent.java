package com.helloai.core.shared.event;

import lombok.Getter;

/**
 * 子任务已分配事件。
 *
 * <p>用于在事务提交后异步衔接后续动作，例如 API_KEY_LLM 的平台内自动执行。</p>
 */
@Getter
public class SubTaskAssignedEvent {

    private final Long subTaskId;
    private final Long agentId;

    public SubTaskAssignedEvent(Long subTaskId, Long agentId) {
        this.subTaskId = subTaskId;
        this.agentId = agentId;
    }
}
