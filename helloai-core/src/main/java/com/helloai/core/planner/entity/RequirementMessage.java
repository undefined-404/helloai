package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求澄清会话消息（V29）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_message")
public class RequirementMessage extends BaseEntity {

    /** 所属澄清会话 ID */
    private Long conversationId;

    /** 消息角色: user 用户 / assistant LLM 需求分析师 */
    private String role;

    /** 消息正文 */
    private String content;

    /** 会话内序号（从 1 递增） */
    private Integer seq;
}
