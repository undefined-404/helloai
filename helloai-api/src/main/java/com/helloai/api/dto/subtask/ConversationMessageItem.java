package com.helloai.api.dto.subtask;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 子任务执行对话流消息条目（V28 对话流可观测）。
 *
 * <p>用于 GET /api/sub-tasks/{id}/conversation 返回结构，按 seq 升序排列。
 * 承载执行产出全文与自动核验的 Prompt / 分析原文，来源由 toolName 区分：
 * sub_task_execute / sub_task_execute_failed / subtask_review_prompt / subtask_review_verdict。</p>
 *
 * <p>所属端点：
 * <ul>
 *   <li>GET /api/sub-tasks/{id}/conversation</li>
 * </ul>
 * </p>
 */
@Data
public class ConversationMessageItem {

    /** 消息 ID（雪花 Long） */
    private Long id;

    /** 消息角色：system/user/assistant/tool */
    private String role;

    /** 发送者类型：platform/agent/human */
    private String senderType;

    /** 发送者 Agent ID，可能为空（平台侧消息） */
    private Long senderId;

    /** 消息正文（执行产出/核验 Prompt/核验分析，全文不截断） */
    private String content;

    /** 内容类型：text/code/image/tool_call/tool_result */
    private String contentType;

    /** 消息来源标记，前端按此打标签 */
    private String toolName;

    /** 消息序号（子任务内递增） */
    private Integer seq;

    /** 创建时间戳 */
    private OffsetDateTime createTime;
}
