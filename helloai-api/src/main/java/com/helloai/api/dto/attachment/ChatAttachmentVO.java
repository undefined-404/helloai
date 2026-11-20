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

    /**
     * 附件来源：{@code user}=用户上传（输入框上方胶囊条）；
     * {@code platform}=平台生成产物（右侧「产物」侧栏）。
     *
     * <p>判定依据：用户上传端点有登录态、必写 {@code uploader_user_id}；
     * 平台产物（技能执行 {@code saveArtifact}）不传上传者、该列为 null。
     * 注：V123 之前的历史上传数据 uploader_user_id 同样为 null，
     * 会被归入 platform —— 存量场景少，且这些数据不会再被当输入复用，可接受。</p>
     */
    private String source;

    private OffsetDateTime createTime;

    /**
     * `MA-8`：疑似敏感内容命中条数（<b>三态</b>，前端须区分）。
     *
     * <ul>
     *   <li>{@code null} = 未扫描（解析未完成 / SKIPPED / 扫描开关关闭）→ 不提示；</li>
     *   <li>{@code 0} = 扫过、未命中 → 不提示；</li>
     *   <li>{@code >0} = 命中，前端提示「检测到 N 处疑似敏感信息（不阻断）」。</li>
     * </ul>
     *
     * <p>刻意<b>不返回命中位置与原文</b>：那等于把密钥再复制一遍到界面上（二次泄露）。
     * 用户需要核对时自己去打开原文件——平台只负责「告知这里有问题」。</p>
     */
    private Integer sensitiveScanHitCount;

    /** `MA-8`：命中规则 code（逗号分隔）；{@code null} = 未命中或未扫描。 */
    private String sensitiveScanRules;

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
        vo.setSource(entity.getUploaderUserId() == null ? "platform" : "user");
        vo.setCreateTime(entity.getCreateTime());
        vo.setSensitiveScanHitCount(entity.getSensitiveScanHitCount());
        vo.setSensitiveScanRules(entity.getSensitiveScanRules());
        return vo;
    }

    public static List<ChatAttachmentVO> fromList(List<ChatAttachment> entities) {
        return entities == null ? List.of() : entities.stream().map(ChatAttachmentVO::from).toList();
    }
}
