package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 结构化多轮对话消息。
 * 替代 conversation_archive 作为活跃对话的持久化存储。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("conversation_message")
public class ConversationMessage extends BaseEntity {

    /** 关联子任务 ID */
    private Long subTaskId;

    /** 消息幂等标识，全局唯一 */
    private String messageId;

    /** 消息角色: system/user/assistant/tool */
    private String role;

    /** 发送者类型: platform/agent/human */
    private String senderType;

    /** 发送者 ID (agent_id) */
    private Long senderId;

    /** 消息正文 */
    private String content;

    /** 内容类型: text/code/image/tool_call/tool_result */
    private String contentType;

    /** 回复哪条消息，构建对话树 */
    private Long replyToId;

    /** 关联的 function call ID */
    private String toolCallId;

    /** 调用的工具名称 */
    private String toolName;

    /** Token 估算数量 */
    private Integer tokenCount;

    /** 关联附件ID列表 (JSON数组字符串) */
    private String attachmentIds;

    /** 消息序号 (子任务内递增) */
    private Integer seq;
}
