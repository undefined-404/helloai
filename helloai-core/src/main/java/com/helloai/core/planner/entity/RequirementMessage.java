package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    /** 消息角色: user 用户 / assistant LLM 需求分析师 */
    private String role;

    /** 消息正文 */
    private String content;

    /** 会话内序号（从 1 递增） */
    private Integer seq;

    /**
     * 结构化附加数据（JSON 文本，V33）。一列两用：
     * assistant 行存结构化问题 {@code {"mode","progress","questions":[...]}}，
     * user 行存选择快照 {@code {"selections":[...]}}；纯文本消息为 NULL。
     */
    private String payload;
}
