package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.agent.entity.ConversationMessage;
import com.helloai.core.agent.mapper.ConversationMessageMapper;
import com.helloai.core.agent.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 多轮对话消息服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMessageMapper, ConversationMessage>
        implements ConversationService {

    /**
     * 向子任务对话追加一条消息（REQUIRES_NEW 独立事务，失败不阻断主链路）。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ConversationMessage addMessage(Long subTaskId, Long senderId,
                                          String role, String senderType,
                                          String content, String toolName) {
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
        msg.setToolName(toolName);
        msg.setSeq(nextSeq);
        save(msg);

        log.debug("对话消息追加: subTaskId={}, seq={}, role={}", subTaskId, msg.getSeq(), role);
        return msg;
    }

    /**
     * 获取子任务的完整对话历史（按序号排序）
     */
    @Override
    public List<ConversationMessage> getMessages(Long subTaskId) {
        return lambdaQuery()
                .eq(ConversationMessage::getSubTaskId, subTaskId)
                .orderByAsc(ConversationMessage::getSeq)
                .list();
    }

    /**
     * 统计子任务对话的 Token 总数
     */
    @Override
    public int getTotalTokens(Long subTaskId) {
        return lambdaQuery()
                .eq(ConversationMessage::getSubTaskId, subTaskId)
                .list()
                .stream()
                .mapToInt(m -> m.getTokenCount() != null ? m.getTokenCount() : 0)
                .sum();
    }
}
