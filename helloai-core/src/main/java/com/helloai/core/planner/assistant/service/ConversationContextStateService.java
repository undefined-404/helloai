package com.helloai.core.planner.assistant.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.planner.assistant.entity.ConversationContextState;

/**
 * 会话上下文压缩状态薄 CRUD（PA-12）。
 */
public interface ConversationContextStateService extends IService<ConversationContextState> {

    /**
     * 取会话的压缩状态；不存在则初始化一行（默认阈值 / 保留比例）。
     *
     * @param conversationId 会话 id
     * @param ownerId        归属用户 id（可空）
     */
    ConversationContextState getOrInit(Long conversationId, Long ownerId);

    /** 只读会话压缩状态；不存在返回 null（不建行，供 prompt 渲染等只读路径使用）。 */
    ConversationContextState getByConversation(Long conversationId);
}