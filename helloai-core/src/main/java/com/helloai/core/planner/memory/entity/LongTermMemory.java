package com.helloai.core.planner.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.LongTermMemoryType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 长期记忆实体（N-009，C5-S1）。
 *
 * <p>摘要式记忆：content 只存压缩摘要（非原始对话，差距表原则）；
 * ref_type+ref_id 唯一约束防重复归档。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("long_term_memory")
public class LongTermMemory extends BaseEntity {

    /** SESSION / TASK / USER。 */
    private LongTermMemoryType type;

    /** 归属：conversation:{id} / task:{id} / user:{id}。 */
    private String scopeKey;

    private String title;

    /** 摘要正文。 */
    private String content;

    /** 来源引用：REQUIREMENT_CONVERSATION / TASK_RUNNING_SPEC。 */
    private String refType;

    private Long refId;

    /** 关键词/主题（recall 匹配用）。 */
    private String tag;

    private OffsetDateTime memoryTime;
}
