package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.entity.AgentInbox;
import com.helloai.core.event.InboxMessageCreatedEvent;
import com.helloai.core.mapper.AgentInboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 收件箱服务。
 * 负责将 MQ 事件投递到各 Agent 的持久化收件箱，支持离线积攒、上线补处理。
 *
 * 核心设计：同一 (eventId, agentId) 最多投递一次（利用联合唯一约束）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentInboxService extends ServiceImpl<AgentInboxMapper, AgentInbox> {

    private final ApplicationEventPublisher eventPublisher;

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
    @Transactional(rollbackFor = Exception.class)
    public void send(Long agentId, String eventId, String eventType,
                     String title, String summary,
                     String refType, Long refId, String priority) {
        AgentInbox inbox = new AgentInbox();
        inbox.setAgentId(agentId);
        inbox.setEventId(eventId);
        inbox.setEventType(eventType);
        inbox.setTitle(title);
        inbox.setSummary(summary);
        inbox.setRefType(refType);
        inbox.setRefId(refId);
        inbox.setIsRead(0);
        inbox.setIsArchived(0);
        inbox.setPriority(priority != null ? priority : "NORMAL");

        try {
            save(inbox);
        } catch (DuplicateKeyException e) {
            // (event_id, agent_id) 联合唯一约束 → 已投递，跳过；重复投递不再响铃
            log.debug("收件箱消息已存在，跳过: eventId={}, agentId={}", eventId, agentId);
            return;
        }

        // 收件箱首次落库成功 → 发布事件驱动门铃响铃（AFTER_COMMIT 异步，尽力而为）。
        // 发布点在 @Transactional 方法内，@TransactionalEventListener 将在本事务提交后才触发，
        // 保证"先落库、后响铃"，门铃丢失也可由 pullTasks 轮询兜底。
        eventPublisher.publishEvent(new InboxMessageCreatedEvent(
                agentId, eventId, eventType, refType, refId));
    }

    /**
     * 查询 Agent 未读消息列表
     */
    public List<AgentInbox> getUnread(Long agentId, int limit) {
        return lambdaQuery()
                .eq(AgentInbox::getAgentId, agentId)
                .eq(AgentInbox::getIsRead, 0)
                .eq(AgentInbox::getIsArchived, 0)
                .orderByDesc(AgentInbox::getPriority)
                .orderByDesc(AgentInbox::getCreateTime)
                .last("LIMIT " + Math.min(limit, 500))
                .list();
    }

    /**
     * 未读消息数量
     */
    public long countUnread(Long agentId) {
        return lambdaQuery()
                .eq(AgentInbox::getAgentId, agentId)
                .eq(AgentInbox::getIsRead, 0)
                .eq(AgentInbox::getIsArchived, 0)
                .count();
    }

    /**
     * 标记已读
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long agentId, Long inboxId) {
        AgentInbox inbox = getById(inboxId);
        if (inbox == null) {
            log.debug("markRead 幂等: inbox 不存在, id={}", inboxId);
            return;
        }
        if (!inbox.getAgentId().equals(agentId)) {
            log.debug("markRead 幂等: agent 不匹配, expected={}, actual={}", agentId, inbox.getAgentId());
            return;
        }
        lambdaUpdate()
                .eq(AgentInbox::getId, inboxId)
                .eq(AgentInbox::getAgentId, agentId)
                .set(AgentInbox::getIsRead, 1)
                .set(AgentInbox::getReadAt, OffsetDateTime.now())
                .update();
    }

    /**
     * 归档消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void markArchived(Long agentId, Long inboxId) {
        AgentInbox inbox = getById(inboxId);
        if (inbox == null) {
            log.debug("markArchived 幂等: inbox 不存在, id={}", inboxId);
            return;
        }
        if (!inbox.getAgentId().equals(agentId)) {
            log.debug("markArchived 幂等: agent 不匹配, expected={}, actual={}", agentId, inbox.getAgentId());
            return;
        }
        lambdaUpdate()
                .eq(AgentInbox::getId, inboxId)
                .eq(AgentInbox::getAgentId, agentId)
                .set(AgentInbox::getIsArchived, 1)
                .update();
    }
}
