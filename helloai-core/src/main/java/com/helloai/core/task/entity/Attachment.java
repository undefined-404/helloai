package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AttachmentStatus;
import com.helloai.common.constant.AttachmentVisibility;
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

    /**
     * 可见范围（V99）：PERSONAL=仅上传者 / TASK=上传者+所属根任务团队（默认）/ PUBLIC=预留未启用。
     *
     * <p>权限判定<b>不直接用本字段比对任务</b>——统一走
     * {@code AttachmentVisibilityPolicy.canRead(...)}，内部以「本字段（声明范围）×
     * Task-Team 成员关系」判定，故将来层级演进不需改判据。</p>
     */
    private AttachmentVisibility visibility;

    /**
     * 上传者 Agent ID（V100）：由 {@code AttachmentService#register} 写入；平台侧/人工上传为空。
     *
     * <p>支撑 {@link AttachmentVisibility#PERSONAL}（仅上传者）以及 TASK 分支的
     * "上传者恒可读自传"兜底（agent 被改派换下后仍能读自己的旧产出）。</p>
     */
    private Long uploaderAgentId;

    /** 回填字段（不落库）：主任务 ID 与标题、子任务标题，供附件管理按层级浏览时展示名称。 */
    @TableField(exist = false)
    private Long taskId;
    @TableField(exist = false)
    private String taskTitle;
    @TableField(exist = false)
    private String subTaskTitle;
}