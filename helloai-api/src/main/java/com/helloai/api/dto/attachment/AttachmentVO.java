package com.helloai.api.dto.attachment;

import com.helloai.common.constant.AttachmentStatus;
import com.helloai.core.task.entity.Attachment;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 附件响应投影（§11.3：API 层不直接暴露数据库 Entity）。
 *
 * <p><b>字段类型为何与实体逐字对齐</b>：本 VO 替代原先把 {@code Attachment} 实体直接
 * 塞进响应体的写法，以保证 {@code id}/{@code subTaskId}/{@code taskId} 等 <b>ID</b> 字段的
 * 线上格式不变（字符串 ⇒ 前端雪花 ID 不丢精度），前端零改动、无接口版本迁移成本。</p>
 *
 * <p><b>{@code fileSize} 的格式（2026-10-07 口径变更）</b>：{@code fileSize} <b>不是 ID</b>，
 * 按 {@code JacksonConfig} 的「两层口径」由属性修饰器自动改为 JSON <b>数字</b>（非 ID 命名的
 * {@code Long} 属性一律回退为数字）。真正决定线上格式的是全局序列化口径而非 Java 字段类型
 * （{@code Long} 与 {@code long} 都会命中默认层），故此处保留 {@code Long} 以与实体一致、
 * 避免额外的映射代码。前端 {@code types/entities.ts#Attachment} 本就把 {@code fileSize}
 * 声明为 {@code number}，且全部消费点（{@code formatSize} / {@code fmtSize} /
 * {@code filePreview}）均为数值运算，对数字字符串同样成立 ⇒ 该变更对前端<b>行为中性</b>。</p>
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
