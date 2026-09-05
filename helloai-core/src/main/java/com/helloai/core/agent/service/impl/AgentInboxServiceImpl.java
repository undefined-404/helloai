package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentInboxProperties;
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
    /** N-008 消息 TTL 单一来源（helloai.agent.inbox.expire-hours，默认 168h）。 */
    private final AgentInboxProperties inboxProperties;

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
        // N-008 消息生命周期（Phase 2 A3）：落库即写 TTL。expireHours <= 0 视为关闭（留 NULL，存量语义）
        if (inboxProperties.getExpireHours() > 0) {
            inbox.setExpireTime(OffsetDateTime.now().plusHours(inboxProperties.getExpireHours()));
        }

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
     *
     * <p>N-008（Phase 2 A3）：过滤已过期消息（expire_time IS NULL OR &gt; now），
     * 防清理任务周期窗口内投递过期消息；存量 NULL 消息行为不变。</p>
     */
    @Override
    public List<AgentInbox> getUnread(Long agentId, int limit) {
        return lambdaQuery()
                .eq(AgentInbox::getAgentId, agentId)
                .eq(AgentInbox::getIsRead, 0)
                .eq(AgentInbox::getIsArchived, 0)
                .and(w -> w.isNull(AgentInbox::getExpireTime)
                        .or().gt(AgentInbox::getExpireTime, OffsetDateTime.now()))
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
     * 未读消息数量（与 {@link #getUnread} 同口径：过滤已过期）
     */
    @Override
    public long countUnread(Long agentId) {
        return lambdaQuery()
                .eq(AgentInbox::getAgentId, agentId)
                .eq(AgentInbox::getIsRead, 0)
                .eq(AgentInbox::getIsArchived, 0)
                .and(w -> w.isNull(AgentInbox::getExpireTime)
                        .or().gt(AgentInbox::getExpireTime, OffsetDateTime.now()))
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

    /**
     * 过期归档：expire_time 已过且未归档的消息批量软删（is_archived=1）。
     *
     * <p>先查后更（SELECT LIMIT + 按 id 集中 UPDATE，参照 {@code AgentDutyLeaseServiceImpl.expireLeases}
     * 的批量模式）：PostgreSQL 不支持 {@code UPDATE ... LIMIT}，LIMIT 只能落在 SELECT 上；
     * UPDATE 侧重验 is_archived=0，与并发手动归档天然无冲突（谁先归档谁生效）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int archiveExpired(int batchLimit) {
        if (batchLimit <= 0) {
            return 0;
        }
        List<Long> expiredIds = lambdaQuery()
                .lt(AgentInbox::getExpireTime, OffsetDateTime.now())
                .eq(AgentInbox::getIsArchived, 0)
                .last("LIMIT " + Math.min(batchLimit, 1000))
                .list()
                .stream()
                .map(AgentInbox::getId)
                .toList();
        if (expiredIds.isEmpty()) {
            return 0;
        }
        return baseMapper.update(null,
                Wrappers.<AgentInbox>lambdaUpdate()
                        .in(AgentInbox::getId, expiredIds)
                        .eq(AgentInbox::getIsArchived, 0)
                        .set(AgentInbox::getIsArchived, 1));
    }
}
