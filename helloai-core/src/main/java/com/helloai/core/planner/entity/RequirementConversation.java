package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求澄清会话（对话式新建任务入口，V29）。
 *
 * <p>与 conversation_message 体系无关：那套挂在 sub_task 上（NOT NULL 外键），
 * 无法承载"任务创建前"的澄清对话，故独立建表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_conversation")
public class RequirementConversation extends BaseEntity {

    /** 会话标题（首条用户消息截断） */
    private String title;

    /** 会话状态: ACTIVE 进行中 / FINALIZED 已终稿建任务 / ABANDONED 已放弃 */
    private String status;

    /** 终稿确认后创建的任务 ID（软引用无 FK，删任务后允许悬挂） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** LLM 最近一次终稿的任务标题（等用户确认） */
    private String finalTitle;

    /** LLM 最近一次终稿的需求描述（等用户确认） */
    private String finalDescription;

    /** 用户消息轮数（服务端硬上限防失控） */
    private Integer roundCount;

    /** 手动指定的 Planner Agent ID（软引用无 FK；NULL 表示系统自动选择，V31） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long plannerAgentId;

    /**
     * 会话级联网搜索开关（Flyway V34 新增）。
     * <p>首轮 LLM 调用前（{@code roundCount=0}）若本字段为 NULL/true，
     * 服务端会预检索行业资料/竞品/技术方案后注入 {@code {{WEB_SEARCH_CONTEXT}}} 占位符。
     * 失败一律降级跳过，不阻断澄清流程；老数据 NULL 视为默认开启。</p>
     */
    private Boolean webSearchEnabled;
}
