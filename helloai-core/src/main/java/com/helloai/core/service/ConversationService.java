package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.entity.ConversationMessage;
import com.helloai.core.mapper.ConversationMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 多轮对话消息服务。
 * 替代 conversation_archive 作为活跃对话的持久化存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService extends ServiceImpl<ConversationMessageMapper, ConversationMessage> {

    /**
     * 向子任务对话追加一条消息
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationMessage addMessage(Long subTaskId, Long senderId,
                                           String role, String senderType,
                                           String content) {
        // 计算下一个序号
        ConversationMessage lastMsg = lambdaQuery()
                .eq(ConversationMessage::getSubTaskId, subTaskId)
                .orderByDesc(ConversationMessage::getSeq)
                .last("LIMIT 1")
                .one();
        int nextSeq = (lastMsg != null && lastMsg.getSeq() != null) ? lastMsg.getSeq() + 1 : 1;

        ConversationMessage msg = new ConversationMessage();
        msg.setSubTaskId(subTaskId);
        msg.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        msg.setRole(role);
        msg.setSenderType(senderType);
        msg.setSenderId(senderId);
        msg.setContent(content);
        msg.setContentType("text");
        msg.setSeq(nextSeq);
        save(msg);

        log.debug("对话消息追加: subTaskId={}, seq={}, role={}", subTaskId, msg.getSeq(), role);
        return msg;
    }

    /**
     * 获取子任务的完整对话历史（按序号排序）
     */
    public List<ConversationMessage> getMessages(Long subTaskId) {
        return lambdaQuery()
                .eq(ConversationMessage::getSubTaskId, subTaskId)
                .orderByAsc(ConversationMessage::getSeq)
                .list();
    }

    /**
     * 统计子任务对话的 Token 总数
     */
    public int getTotalTokens(Long subTaskId) {
        return lambdaQuery()
                .eq(ConversationMessage::getSubTaskId, subTaskId)
                .list()
                .stream()
                .mapToInt(m -> m.getTokenCount() != null ? m.getTokenCount() : 0)
                .sum();
    }
}
