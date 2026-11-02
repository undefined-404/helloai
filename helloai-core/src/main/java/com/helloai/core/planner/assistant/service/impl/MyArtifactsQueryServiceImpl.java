package com.helloai.core.planner.assistant.service.impl;

import com.helloai.core.planner.assistant.service.MyArtifactsQueryService;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.shared.util.CurrentUser;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 「我的产物」聚合读取面实现（PA-5，2026-11-02）。
 *
 * <p>两源各自经<b>既有 Service</b>拉取（不跨域直连 Mapper），归一并按 createTime 倒序：
 * <ul>
 *   <li><b>conversation</b>：{@link ChatAttachmentService#listByUploaderUserId} —— 行级
 *       {@code uploader_user_id = 当前用户}；上下文标题取会话标题。
 *   <li><b>execution</b>：{@link AttachmentService#listActiveByTaskOwner} —— 借道
 *       {@code task.owner_id = 当前用户}；上下文标题取任务标题（已由调用方回填）。
 * </ul>
 * 时间倒序后截断到 limit。</p>
 *
 * <p><b>存量归属</b>：execution 源按 D1 直滤 owner（不含 NULL）。PA-1 之前的历史任务
 * owner_id 由迁移 {@code V133__pa_task_owner_backfill.sql} 一次性回填为 admin，故升级后
 * 这些历史产物可见；该回填是单用户环境口径，多租户场景另议。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyArtifactsQueryServiceImpl implements MyArtifactsQueryService {

    /** 未显式传 limit 时的默认每源上限。 */
    private static final int DEFAULT_LIMIT = 50;

    private final ChatAttachmentService chatAttachmentService;
    private final AttachmentService attachmentService;
    private final RequirementConversationService conversationService;

    @Override
    public List<ArtifactItem> myArtifacts(Integer limit) {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            log.debug("myArtifacts: 无登录用户，返回空");
            return List.of();
        }

        int cap = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        List<ArtifactItem> items = new ArrayList<>();
        items.addAll(conversationItems(userId, cap));
        items.addAll(executionItems(userId, cap));

        // 兼容互相戳脏/并发下 createTime 为 null 的行：null 沉底。
        items.sort(Comparator
                .comparing(ArtifactItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ArtifactItem::id, Comparator.reverseOrder()));
        if (items.size() > cap) {
            return items.subList(0, cap);
        }
        return items;
    }

    @Override
    public List<ArtifactItem> conversationArtifacts(Integer limit) {
        Long userId = CurrentUser.userId();
        if (userId == null) {
            log.debug("conversationArtifacts: 无登录用户，返回空");
            return List.of();
        }
        int cap = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        return conversationItems(userId, cap);
    }

    private List<ArtifactItem> conversationItems(Long userId, int cap) {
        List<ChatAttachment> rows = chatAttachmentService.listByUploaderUserId(userId, cap);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> convIds = rows.stream().map(ChatAttachment::getConversationId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, String> titleById = convIds.isEmpty() ? Collections.emptyMap()
                : conversationService.listByIds(convIds).stream()
                        .collect(Collectors.toMap(RequirementConversation::getId,
                                RequirementConversation::getTitle, (a, b) -> a));
        return rows.stream()
                .filter(c -> c.getUploaderUserId() != null && Objects.equals(c.getUploaderUserId(), userId))
                .map(c -> new ArtifactItem(
                        SOURCE_CONVERSATION,
                        c.getId(),
                        c.getFileName(),
                        c.getFileType(),
                        c.getMimeType(),
                        c.getFileSize(),
                        titleById.get(c.getConversationId()),
                        c.getConversationId(),
                        c.getCreateTime()))
                .collect(Collectors.toList());
    }

    private List<ArtifactItem> executionItems(Long userId, int cap) {
        List<Attachment> rows = attachmentService.listActiveByTaskOwner(userId, cap);
        return rows.stream()
                .map(a -> new ArtifactItem(
                        SOURCE_EXECUTION,
                        a.getId(),
                        a.getFileName(),
                        a.getFileType(),
                        a.getMimeType(),
                        a.getFileSize(),
                        a.getTaskTitle(),
                        a.getTaskId(),
                        a.getCreateTime()))
                .collect(Collectors.toList());
    }
}