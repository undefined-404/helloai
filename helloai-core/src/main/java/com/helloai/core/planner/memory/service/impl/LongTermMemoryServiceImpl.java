package com.helloai.core.planner.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.LongTermMemoryType;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.memory.ConversationMemorySummarizer;
import com.helloai.core.planner.memory.entity.LongTermMemory;
import com.helloai.core.planner.memory.mapper.LongTermMemoryMapper;
import com.helloai.core.planner.memory.service.LongTermMemoryService;
import com.helloai.core.shared.util.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 长期记忆服务实现（N-009，C5-S1/S2/S3）。
 *
 * <p>摘要式记忆（非原始对话）；ref 唯一防重复归档；recall 有限条数受控检索。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryServiceImpl
        extends ServiceImpl<LongTermMemoryMapper, LongTermMemory>
        implements LongTermMemoryService {

    private static final String REF_TYPE_CONVERSATION = "REQUIREMENT_CONVERSATION";

    private final LongTermMemoryMapper memoryMapper;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void archiveSession(RequirementConversation conversation, List<RequirementMessage> messages) {
        if (conversation == null || conversation.getId() == null) {
            return;
        }
        Long count = memoryMapper.selectCount(new LambdaQueryWrapper<LongTermMemory>()
                .eq(LongTermMemory::getRefType, REF_TYPE_CONVERSATION)
                .eq(LongTermMemory::getRefId, conversation.getId()));
        if (count != null && count > 0) {
            return; // 幂等：已归档
        }
        ConversationMemorySummarizer.MemorySummary summary =
                ConversationMemorySummarizer.summarize(conversation, messages);
        LongTermMemory memory = new LongTermMemory();
        memory.setType(LongTermMemoryType.SESSION);
        memory.setScopeKey("conversation:" + conversation.getId());
        memory.setTitle(summary.title());
        memory.setContent(summary.content());
        memory.setRefType(REF_TYPE_CONVERSATION);
        memory.setRefId(conversation.getId());
        memory.setTag(summary.tag());
        memory.setMemoryTime(OffsetDateTime.now());
        // PA-1：会话摘要继承会话 owner（宽松并入：owner 为 NULL 的老会话不参与个人过滤）
        memory.setOwnerId(conversation.getOwnerId());
        memoryMapper.insert(memory);
        log.info("会话摘要已归档: conversationId={}, title={}", conversation.getId(), summary.title());
    }

    @Override
    public List<LongTermMemory> searchForRecall(String keyword, int limit) {
        int safeLimit = limit <= 0 ? 5 : Math.min(limit, 20);
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String kw = keyword.trim();
        Long userId = CurrentUser.userId();
        return memoryMapper.selectList(new LambdaQueryWrapper<LongTermMemory>()
                .and(w -> w.like(LongTermMemory::getTag, kw)
                        .or().like(LongTermMemory::getTitle, kw)
                        .or().like(LongTermMemory::getContent, kw))
                // PA-1 宽松并入：仅当前用户可见「自己的」或「无归属」，未登录取全部
                .and(userId != null, w -> w.isNull(LongTermMemory::getOwnerId)
                        .or().eq(LongTermMemory::getOwnerId, userId))
                .orderByDesc(LongTermMemory::getMemoryTime)
                .last("LIMIT " + safeLimit));
    }

    @Override
    public IPage<LongTermMemory> page(long page, long size, LongTermMemoryType type) {
        Long userId = CurrentUser.userId();
        LambdaQueryWrapper<LongTermMemory> wrapper = new LambdaQueryWrapper<LongTermMemory>()
                .eq(type != null, LongTermMemory::getType, type)
                // PA-1 宽松并入：仅当前用户可见「自己的」或「无归属」，未登录取全部
                .and(userId != null, w -> w.isNull(LongTermMemory::getOwnerId)
                        .or().eq(LongTermMemory::getOwnerId, userId))
                .orderByDesc(LongTermMemory::getMemoryTime);
        return baseMapper.selectPage(new Page<>(page, size), wrapper);
    }
}
