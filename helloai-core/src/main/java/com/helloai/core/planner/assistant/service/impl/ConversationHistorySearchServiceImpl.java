package com.helloai.core.planner.assistant.service.impl;

import com.helloai.core.planner.assistant.service.ConversationHistorySearchService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link ConversationHistorySearchService} 实现（PA-11）。
 *
 * <p>真相源 {@code requirement_message}：按 {@code owner_id} 过滤（PA-1），
 * 「最近工作」两次查询（会话 + 消息）批量取数，避免 N+1；「关键词检索」用
 * {@code LIKE} 组合（一期）。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationHistorySearchServiceImpl implements ConversationHistorySearchService {

    /** 单条片段上限（字符）。 */
    private static final int SNIPPET_MAX = 400;

    /** 单次检索最多使用的关键词数。 */
    private static final int MAX_TOKENS = 5;

    private final RequirementConversationService conversationService;
    private final RequirementMessageService messageService;

    @Override
    public List<HistoryHit> recentWork(Long ownerId, int limit) {
        if (ownerId == null || limit <= 0) {
            return List.of();
        }
        List<RequirementConversation> conversations = conversationService.lambdaQuery()
                .and(w -> w.isNull(RequirementConversation::getOwnerId)
                        .or().eq(RequirementConversation::getOwnerId, ownerId))
                .orderByDesc(RequirementConversation::getUpdateTime)
                .last("LIMIT " + limit)
                .list();
        if (conversations.isEmpty()) {
            return List.of();
        }
        // 按会话各取最新一条 user 消息（N=limit，通常 5）：避免长会话的近期消息把
        // 其他会话挤出全局 LIMIT 窗口（2026-11-03 复核 L-2 修正）。
        List<HistoryHit> hits = new ArrayList<>();
        for (RequirementConversation c : conversations) {
            RequirementMessage m = messageService.lambdaQuery()
                    .eq(RequirementMessage::getConversationId, c.getId())
                    .eq(RequirementMessage::getRole, "user")
                    .orderByDesc(RequirementMessage::getSeq)
                    .last("LIMIT 1")
                    .one();
            if (m != null) {
                hits.add(new HistoryHit(c.getId(), c.getTitle() == null ? "" : c.getTitle(),
                        m.getRole(), truncate(m.getContent()), m.getCreateTime()));
            }
        }
        return hits;
    }

    @Override
    public List<HistoryHit> search(Long ownerId, String query, int limit) {
        if (ownerId == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return List.of();
        }
        var wrapper = messageService.lambdaQuery()
                .eq(RequirementMessage::getOwnerId, ownerId);
        wrapper.and(w -> {
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    w.or();
                }
                w.like(RequirementMessage::getContent, tokens.get(i));
            }
        });
        List<RequirementMessage> rows = wrapper
                .orderByDesc(RequirementMessage::getCreateTime)
                .last("LIMIT " + limit)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> conversationIds = rows.stream().map(RequirementMessage::getConversationId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> titleById = conversationIds.isEmpty() ? Map.of()
                : conversationService.listByIds(conversationIds).stream()
                        .collect(Collectors.toMap(RequirementConversation::getId,
                                c -> c.getTitle() == null ? "" : c.getTitle(), (a, b) -> a));
        return rows.stream()
                .map(m -> new HistoryHit(m.getConversationId(), titleById.get(m.getConversationId()),
                        m.getRole(), truncate(m.getContent()), m.getCreateTime()))
                .toList();
    }

    /** 问题切词：按空白与中英文标点切分，取长度 ≥2 的前 {@value #MAX_TOKENS} 个。 */
    static List<String> tokens(String query) {
        String[] parts = query.split("[\\s,，。！？;；:：\\n\\r\\t]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.length() >= 2 && !tokens.contains(token)) {
                tokens.add(token);
            }
            if (tokens.size() >= MAX_TOKENS) {
                break;
            }
        }
        if (tokens.isEmpty() && query.trim().length() >= 2) {
            tokens.add(query.trim());
        }
        return tokens;
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_MAX ? content : content.substring(0, SNIPPET_MAX) + "...";
    }
}