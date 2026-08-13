package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AttachmentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("attachment")
public class Attachment extends BaseEntity {

    private Long subTaskId;
    private String fileName;
    private String fileType;
    private String mimeType;
    private Long fileSize;
    private String bucketName;
    private String objectKey;
    private String storageUrl;
    private String previewUrl;
    private AttachmentStatus status;

    /** 回填字段（不落库）：主任务 ID 与标题、子任务标题，供附件管理按层级浏览时展示名称。 */
    @TableField(exist = false)
    private Long taskId;
    @TableField(exist = false)
    private String taskTitle;
    @TableField(exist = false)
    private String subTaskTitle;
}
