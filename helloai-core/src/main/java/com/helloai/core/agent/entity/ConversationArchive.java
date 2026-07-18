package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("conversation_archive")
public class ConversationArchive extends BaseEntity {

    private Long subTaskId;
    private String content;
    private Integer messageCount;
    private Integer totalTokens;
    private OffsetDateTime archiveTime;
}
