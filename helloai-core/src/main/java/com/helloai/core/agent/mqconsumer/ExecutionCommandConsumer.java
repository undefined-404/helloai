package com.helloai.core.agent.mqconsumer;

import com.helloai.core.agent.domain.ExecutionCommand;

/**
 * 执行命令消费者扩展点。
 *
 * <p>本轮只建立“调度发命令”的边界，不在这里直接落地真实消费实现。
 * 后续可按本接口补本地事件消费者、DB poller、MQ consumer 等独立执行端。</p>
 */
public interface ExecutionCommandConsumer {

    /**
     * 消费一条执行命令。
     */
    void consume(ExecutionCommand command);
}
