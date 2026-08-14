package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentInbox;
import com.helloai.core.agent.mapper.AgentInboxMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.shared.event.InboxMessageCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 收件箱服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentInboxServiceImpl extends ServiceImpl<AgentInboxMapper, AgentInbox>
        implements AgentInboxService {

    private final ApplicationEventPublisher eventPublisher;
    // 直接注入 Mapper 而非 AgentService，避免 service 层依赖环
    private final AgentMapper agentMapper;

    /**
     * 向指定 Agent 投递收件箱消息。幂等。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void send(Long agentId, String eventId, String eventType,
                     String title, String summary,
                     String refType, Long refId, String priority) {
        // 投递前守卫：Agent 不存在（防御）或 API_KEY_LLM（消费链路走 outbox→MQ，收件箱无人读）时跳过
        Agent target = agentMapper.selectById(agentId);
        if (target == null) {
            log.debug("收件箱跳过投递: agent 不存在, agentId={}, eventId={}", agentId, eventId);
            return;
        }
        if (target.getAccessType() == AgentAccessType.API_KEY_LLM) {
            log.debug("收件箱跳过投递: API_KEY_LLM 不消费收件箱, agentId={}, eventId={}", agentId, eventId);
            return;
        }

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
    @Override
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
     * 查询 Agent 最近已读消息列表（按 read_time 倒序）。
     */
    @Override
    public List<AgentInbox> getRecentRead(Long agentId, int limit) {
        return lambdaQuery()
                .eq(AgentInbox::getAgentId, agentId)
                .eq(AgentInbox::getIsRead, 1)
                .eq(AgentInbox::getIsArchived, 0)
                .orderByDesc(AgentInbox::getReadTime)
                .last("LIMIT " + Math.min(limit, 500))
                .list();
    }

    /**
     * 未读消息数量
     */
    @Override
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
    @Override
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
                .set(AgentInbox::getReadTime, OffsetDateTime.now())
                .update();
    }

    /**
     * 归档消息
     */
    @Override
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
