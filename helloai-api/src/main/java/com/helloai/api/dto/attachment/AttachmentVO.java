package com.helloai.api.dto.attachment;

import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.task.entity.Attachment;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 附件响应投影（§11.3：API 层不直接暴露数据库 Entity）。
 *
 * <p><b>为什么字段类型与实体逐字对齐</b>：本 VO 替代原先把 {@code Attachment} 实体直接
 * 塞进响应体的写法，而前端契约（{@code helloai-ui/src/types/entities.ts#Attachment}）
 * 已按实体序列化结果定型。Jackson 全局配置把 {@code Long} 序列化为字符串
 * （见 {@code JacksonConfig#jacksonLongToStringCustomizer}），故此处保留 {@code Long}
 * 字段类型，可使新响应与原实体响应<b>逐字节一致</b>，前端零改动、无接口版本迁移成本。
 * 若改用 {@code long}/{@code Number}，会静默改变 {@code id}/{@code fileSize} 的线上格式，
 * 属于破坏性变更，必须避免。</p>
 *
 * <p><b>刻意不投影的字段</b>：{@code deleted}/{@code createBy}/{@code updateBy}/{@code remark}
 * 属持久化内部控制字段；{@code visibility}/{@code uploaderAgentId} 属可见性判定的内部输入
 * （判定统一走 {@code AttachmentVisibilityPolicy}）。对外投影只保留客户端实际消费的字段，
 * 避免把"归属与可见性内部状态"变成对外契约而被反向依赖。</p>
 */
@Data
public class AttachmentVO {

    private Long id;
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
    private OffsetDateTime createTime;

    /** 回填字段（不落库）：主任务 ID 与标题、子任务标题，供附件管理按层级浏览展示。 */
    private Long taskId;
    private String taskTitle;
    private String subTaskTitle;

    public static AttachmentVO from(Attachment a) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(a.getId());
        vo.setSubTaskId(a.getSubTaskId());
        vo.setFileName(a.getFileName());
        vo.setFileType(a.getFileType());
        vo.setMimeType(a.getMimeType());
        vo.setFileSize(a.getFileSize());
        vo.setBucketName(a.getBucketName());
        vo.setObjectKey(a.getObjectKey());
        vo.setStorageUrl(a.getStorageUrl());
        vo.setPreviewUrl(a.getPreviewUrl());
        vo.setStatus(a.getStatus());
        vo.setCreateTime(a.getCreateTime());
        vo.setTaskId(a.getTaskId());
        vo.setTaskTitle(a.getTaskTitle());
        vo.setSubTaskTitle(a.getSubTaskTitle());
        return vo;
    }

    public static List<AttachmentVO> fromList(List<Attachment> list) {
        return list.stream().map(AttachmentVO::from).toList();
    }
}
