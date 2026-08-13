package com.helloai.core.planner.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.planner.entity.RequirementMessage;

import java.util.List;

/**
 * 需求澄清会话消息薄 CRUD 服务。
 */
public interface RequirementMessageService extends IService<RequirementMessage> {

    /**
     * 向澄清会话追加一条消息（seq = 当前最大序号 + 1）。
     *
     * <p>照 ConversationService.addMessage 的 seq 生成范式；澄清对话是主链路本身
     * （非增量副本），失败直接抛给调用方，不需要 REQUIRES_NEW 独立事务。</p>
     */
    RequirementMessage addMessage(Long conversationId, String role, String content);

    /**
     * 追加带结构化附加数据的消息（V33）。
     *
     * @param payload JSON 文本（assistant=结构化问题，user=选择快照）；纯文本消息传 null
     */
    RequirementMessage addMessage(Long conversationId, String role, String content, String payload);

    /** 获取会话完整消息列表（按序号升序）。 */
    List<RequirementMessage> listByConversation(Long conversationId);
}
