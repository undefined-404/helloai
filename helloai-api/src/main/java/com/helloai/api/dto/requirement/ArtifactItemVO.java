package com.helloai.api.dto.requirement;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.core.planner.assistant.service.MyArtifactsQueryService.ArtifactItem;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 「我的产物」聚合条目响应视图（PA-5）。
 *
 * <p>只做查询聚合展示（计划 §0 ④）：不携带下载/预览凭据，也不含存储地址。
 * 下载/预览仍走各自既有端点（{@code /chat-attachments/*} 或 {@code /attachments/*}），
 * 由前端按 {@link #source} 路由。</p>
 */
@Data
public class ArtifactItemVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** conversation | execution —— 前端据此分栏、并路由下载/预览端点。 */
    private String source;

    private String fileName;
    private String fileType;
    private String mimeType;
    private Long fileSize;

    /** 关联上下文标题（会话标题或任务标题）。 */
    private String contextTitle;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long contextId;

    private OffsetDateTime createdAt;

    public static ArtifactItemVO from(ArtifactItem item) {
        if (item == null) {
            return null;
        }
        ArtifactItemVO vo = new ArtifactItemVO();
        vo.setId(item.id());
        vo.setSource(item.source());
        vo.setFileName(item.fileName());
        vo.setFileType(item.fileType());
        vo.setMimeType(item.mimeType());
        vo.setFileSize(item.fileSize());
        vo.setContextTitle(item.contextTitle());
        vo.setContextId(item.contextId());
        vo.setCreatedAt(item.createdAt());
        return vo;
    }

    public static List<ArtifactItemVO> fromList(List<ArtifactItem> items) {
        return items == null ? List.of() : items.stream().map(ArtifactItemVO::from).toList();
    }
}