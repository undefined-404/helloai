package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import com.helloai.core.task.entity.SubTask;

import java.util.List;

/**
 * 事务性 Outbox 事件服务（agent_outbox_event 表）。
 */
public interface AgentOutboxService extends IService<AgentOutboxEvent> {

    /**
     * 创建 Outbox 事件（同业务事务写入，MQ 侧经 AgentEventCompensationTask 投递）。
     */
    AgentOutboxEvent createEvent(SubTask subTask, SubTaskStatus newStatus);

    /**
     * 轮询待投递事件（retryCount < 5，且到 nextRetryTime）。
     */
    List<AgentOutboxEvent> pollPending(int limit);

    /**
     * 标记投递成功（status=SUCCESS，retry_count+1）。
     */
    void markSuccess(Long id);

    /**
     * 标记投递失败（retry_count+1，nextRetryTime+10s）。
     */
    void markFailed(Long id, String error);
}
