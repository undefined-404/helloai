package com.helloai.core.shared.event;

import com.helloai.core.agent.domain.ExecutionCommand;
import lombok.Getter;

/**
 * 执行命令已创建事件。
 *
 * <p>当前由调度侧在命令落库后发布，后续可由独立的
 * {@link com.helloai.core.service.ExecutionCommandConsumer} 消费。</p>
 */
@Getter
public class ExecutionCommandCreatedEvent {

    private final ExecutionCommand command;

    public ExecutionCommandCreatedEvent(ExecutionCommand command) {
        this.command = command;
    }
}
