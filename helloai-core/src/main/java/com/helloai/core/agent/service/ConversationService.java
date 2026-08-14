package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.agent.entity.ConversationMessage;

import java.util.List;

/**
 * 多轮对话消息服务。
 * 替代 conversation_archive 作为活跃对话的持久化存储。
 */
public interface ConversationService extends IService<ConversationMessage> {

    /**
     * 向子任务对话追加一条消息。
     *
     * <p>REQUIRES_NEW 独立事务：对话流是主链路的增量副本，
     * 写入失败由调用方 try/catch 记 warn，绝不阻断执行/核验主事务。</p>
     *
     * @param toolName 消息来源标记（如 sub_task_execute / subtask_review_prompt），可为 null
     */
    ConversationMessage addMessage(Long subTaskId, Long senderId,
                                   String role, String senderType,
                                   String content, String toolName);

    /**
     * 获取子任务的完整对话历史（按序号排序）
     */
    List<ConversationMessage> getMessages(Long subTaskId);

    /**
     * 统计子任务对话的 Token 总数
     */
    int getTotalTokens(Long subTaskId);
}
