package com.helloai.core.planner.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.mapper.RequirementMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 需求澄清会话消息薄 CRUD 服务。
 */
@Slf4j
@Service
public class RequirementMessageService
        extends ServiceImpl<RequirementMessageMapper, RequirementMessage> {

    /**
     * 向澄清会话追加一条消息（seq = 当前最大序号 + 1）。
     *
     * <p>照 ConversationService.addMessage 的 seq 生成范式；澄清对话是主链路本身
     * （非增量副本），失败直接抛给调用方，不需要 REQUIRES_NEW 独立事务。</p>
     */
    public RequirementMessage addMessage(Long conversationId, String role, String content) {
        RequirementMessage lastMsg = lambdaQuery()
                .eq(RequirementMessage::getConversationId, conversationId)
                .orderByDesc(RequirementMessage::getSeq)
                .last("LIMIT 1")
                .one();
        int nextSeq = (lastMsg != null && lastMsg.getSeq() != null) ? lastMsg.getSeq() + 1 : 1;

        RequirementMessage msg = new RequirementMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSeq(nextSeq);
        save(msg);

        log.debug("澄清消息追加: conversationId={}, seq={}, role={}", conversationId, nextSeq, role);
        return msg;
    }

    /** 获取会话完整消息列表（按序号升序）。 */
    public List<RequirementMessage> listByConversation(Long conversationId) {
        return lambdaQuery()
                .eq(RequirementMessage::getConversationId, conversationId)
                .orderByAsc(RequirementMessage::getSeq)
                .list();
    }
}
