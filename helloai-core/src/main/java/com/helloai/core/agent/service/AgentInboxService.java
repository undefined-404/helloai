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

    /**
     * 过期归档：将 expire_time 已过且未归档的消息批量软删（is_archived=1）。
     *
     * <p>N-008 统一消息生命周期：由 {@code InboxExpireCleanupTask} 周期调用，
     * 消除 unread 消息无限堆积；归档保留审计查询能力（fail-close：不丢数据）。</p>
     *
     * @param batchLimit 单轮最大归档条数
     * @return 本轮实际归档条数
     */
    int archiveExpired(int batchLimit);

    // ══════════════════════════════════════════════════════════════
    //  管理端视角（2026-10-18）
    //  ------------------------------------------------------------
    //  为什么需要：原接口全部以 @RequestAttribute("_authId") 取 Agent 身份，
    //  只回答「我这个 Agent 的收件箱」。admin 不是 Agent、没有 _authId
    //  ⇒ 页面永远空（实测 agent_inbox 有 53 条，接口返回 []）。
    //  管理端语义不同（可看全部 / 可按 agent 过滤 / 可跨 agent 标记），
    //  故独立一组方法，**不改动上面 Agent 侧契约**（避免越权面扩大）。
    // ══════════════════════════════════════════════════════════════

    /**
     * 管理端分页查询收件箱（可按 Agent 过滤）。
     *
     * @param agentId  Agent ID；{@code null} = 全部 Agent
     * @param isRead   已读状态过滤：{@code 0} 未读 / {@code 1} 已读 / {@code null} 不限
     * @param includeArchived 是否包含已归档；默认 {@code false}（只看在用消息）
     * @param page     页码（从 1 开始）
     * @param pageSize 每页条数
     */
    com.baomidou.mybatisplus.core.metadata.IPage<AgentInbox> pageForAdmin(
            Long agentId, Integer isRead, boolean includeArchived, int page, int pageSize);

    /** 管理端未读数统计（可按 Agent 过滤；{@code agentId=null} = 全平台）。 */
    long countUnreadForAdmin(Long agentId);

    /** 管理端标记已读：按主键直接改，不做 Agent 归属校验（调用方负责鉴权）。 */
    void markReadById(Long inboxId);

    /** 管理端归档：按主键直接改，不做 Agent 归属校验（调用方负责鉴权）。 */
    void markArchivedById(Long inboxId);
}
