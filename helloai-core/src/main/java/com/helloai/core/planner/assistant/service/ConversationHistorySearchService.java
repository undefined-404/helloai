package com.helloai.core.planner.assistant.service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 跨历史会话检索（PA-11）。
 *
 * <p>为助理对话提供两段上下文：
 * <ul>
 *   <li>{@link #recentWork}：当前用户最近处理过的会话（标题 + 最近一条用户消息），
 *       回答「最近都在忙什么」；</li>
 *   <li>{@link #search}：按用户问题做关键词检索（一期 ILIKE / {@code pg_trgm}），
 *       回答「之前那次关于 X 的对话」。</li>
 * </ul>
 * 真相源为 {@code requirement_message}（PA-1 已带 {@code owner_id}），只读。</p>
 *
 * <p><b>边界</b>：{@code ownerId} 为空返回空（不外泄）；检索一期为关键词匹配，
 * 中文分词与语义召回留二期（{@code tsvector}/向量）。</p>
 */
public interface ConversationHistorySearchService {

    /** 单条历史命中（归一投影，供 Prompt 拼装）。 */
    record HistoryHit(Long conversationId, String conversationTitle, String role,
                      String snippet, OffsetDateTime createdAt) {
    }

    /** 当前用户最近 N 个会话的「最近一条用户消息」。 */
    List<HistoryHit> recentWork(Long ownerId, int limit);

    /** 按问题关键词检索当前用户的历史消息。 */
    List<HistoryHit> search(Long ownerId, String query, int limit);
}