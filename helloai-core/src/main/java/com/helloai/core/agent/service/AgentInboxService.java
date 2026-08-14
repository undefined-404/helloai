package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.agent.entity.AgentInbox;

import java.util.List;

/**
 * Agent 收件箱服务。
 * 负责将 MQ 事件投递到各 Agent 的持久化收件箱，支持离线积攒、上线补处理。
 *
 * <p>核心设计：同一 (eventId, agentId) 最多投递一次（利用联合唯一约束）。
 * API_KEY_LLM Agent 消费链路走 outbox → MQ（后端进程内代消费），在 {@link #send} 咽喉点统一跳过写入。</p>
 */
public interface AgentInboxService extends IService<AgentInbox> {

    /**
     * 向指定 Agent 投递收件箱消息。幂等。
     *
     * @param agentId   目标 Agent ID
     * @param eventId   MQ 事件 ID
     * @param eventType 事件类型
     * @param title     通知标题
     * @param summary   通知摘要
     * @param refType   关联实体类型
     * @param refId     关联实体 ID
     * @param priority  优先级
     */
    void send(Long agentId, String eventId, String eventType,
              String title, String summary,
              String refType, Long refId, String priority);

    /**
     * 查询 Agent 未读消息列表
     */
    List<AgentInbox> getUnread(Long agentId, int limit);

    /**
     * 查询 Agent 最近已读消息列表（按 read_time 倒序），供 pullTasks(includeRead=true) 使用。
     */
    List<AgentInbox> getRecentRead(Long agentId, int limit);

    /**
     * 未读消息数量
     */
    long countUnread(Long agentId);

    /**
     * 标记已读（agent 归属校验 + 幂等）
     */
    void markRead(Long agentId, Long inboxId);

    /**
     * 归档消息（agent 归属校验 + 幂等）
     */
    void markArchived(Long agentId, Long inboxId);
}
