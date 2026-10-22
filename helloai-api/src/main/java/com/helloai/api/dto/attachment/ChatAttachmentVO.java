package com.helloai.api.dto.attachment;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.core.planner.entity.ChatAttachment;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 会话附件响应视图（Controller 层投影，不直接暴露 Entity）。
 *
 * <p>刻意<b>不返回</b> {@code extractedText}：正文可能几十万字符，前端胶囊只需要「解析到没到、
 * 多少字、几页」，正文由 Prompt 注入链路消费。要看内容走预览端点。</p>
 */
@Data
public class ChatAttachmentVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    private String fileName;
    private String fileType;
    private String mimeType;
    private Long fileSize;

    /** PENDING / PARSING / READY / FAILED / SKIPPED（前端据此渲染胶囊状态）。 */
    private String parseStatus;

    /** 失败/跳过原因（可空）；SKIPPED 时前端要显示「未识别」而不是「加载中」。 */
    private String parseError;

    /** 正文实际字符数（READY 后有值）。 */
    private Integer extractedChars;

    private Integer pageCount;

    /** text / tika / vision（后续）。 */
    private String parser;

    private OffsetDateTime createTime;

    public static ChatAttachmentVO from(ChatAttachment entity) {
        if (entity == null) {
            return null;
        }
        ChatAttachmentVO vo = new ChatAttachmentVO();
        vo.setId(entity.getId());
        vo.setConversationId(entity.getConversationId());
        vo.setFileName(entity.getFileName());
        vo.setFileType(entity.getFileType());
        vo.setMimeType(entity.getMimeType());
        vo.setFileSize(entity.getFileSize());
        vo.setParseStatus(entity.getParseStatus() == null ? null : entity.getParseStatus().name());
        vo.setParseError(entity.getParseError());
        vo.setExtractedChars(entity.getExtractedChars());
        vo.setPageCount(entity.getPageCount());
        vo.setParser(entity.getParser());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    public static List<ChatAttachmentVO> fromList(List<ChatAttachment> entities) {
        return entities == null ? List.of() : entities.stream().map(ChatAttachmentVO::from).toList();
    }
}
