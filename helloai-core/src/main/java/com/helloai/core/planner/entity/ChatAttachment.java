package com.helloai.core.planner.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.ChatAttachmentParseStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 需求对话附件（会话级，V121）。
 *
 * <p>与子任务级 {@code attachment} 分表而非加列：后者的可见性策略、交付物判定与补偿任务
 * 都建立在「子任务」锚点上，塞进会话维度会让判据出现第四种情形（详见
 * doc/plan/HelloAI 需求对话附件设计方案.md §3.1）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_attachment")
public class ChatAttachment extends BaseEntity {

    /** 所属会话；新会话「先上传后建会」阶段为空，靠 {@link #clientToken} 占位。 */
    private Long conversationId;

    /** 临时归属（前端生成 UUID）；建会时按此认领回填 conversationId，认领后保留便于幂等重试。 */
    private String clientToken;

    /**
     * 上传者登录用户 id（`ATT-3`，V123）。
     *
     * <p>上传端点有 {@code @SaCheckPermission("conversation:add")}，登录态必然存在，新数据一律写值。
     * 用途：ingest 到 kb 时落到 {@code kb_chunk.user_id}，让检索隔离能区分「同一会话的多个人」；
     * 历史数据为 null（该列 V123 才加）。</p>
     */
    private Long uploaderUserId;

    private String fileName;

    /** 归一化扩展名（小写、不含点）。 */
    private String fileType;

    private String mimeType;

    private Long fileSize;

    /** sha256 十六进制，同一会话内去重（重复上传不重复解析/嵌入）。 */
    private String contentHash;

    private String bucketName;

    private String objectKey;

    /**
     * 平台存储地址（{@code minio://bucket/objectKey} 或 {@code local://...}）。
     *
     * <p>读写一律走 {@code ArtifactStorage} 的 storageUrl 口径，与子任务级 {@code attachment}
     * 表一致；bucketName/objectKey 只是对账与排障用的冗余列，<b>不用于拼装读地址</b>
     * （不同存储实现的 URL 语法不同，拼装会把介质细节泄漏到业务层）。</p>
     */
    private String storageUrl;

    private ChatAttachmentParseStatus parseStatus;

    /** 失败原因（截断到 512），供前端展示与排障；不含堆栈。 */
    private String parseError;

    /** 解析结果正文：文本族为原文，结构化格式为 Markdown。 */
    private String extractedText;

    /** 正文实际字符数（截断后），供注入预算与可观测性使用。 */
    private Integer extractedChars;

    /** 页数（PDF/PPT）；未知为 null。 */
    private Integer pageCount;

    /** 解析器标识：text / tika（后续 vision）。 */
    private String parser;

    /** 文本族判码结果（UTF-8 / GB18030 / ...），排查乱码用。 */
    private String charsetName;

    /** 已入 RAG 时的 source（chat:{conversationId}:{attachmentId}）。 */
    private String kbSource;

    /**
     * `MA-8` 挂点④：附件正文疑似敏感内容的命中条数。
     *
     * <p><b>三态语义必须区分</b>（判据 10「显式声明」）：</p>
     * <ul>
     *   <li>{@code null} = <b>未扫描</b>（解析失败 / SKIPPED / 扫描开关关闭）——前端不显示提示；</li>
     *   <li>{@code 0} = <b>扫过，未命中</b>——前端也不显示提示，但与 null 在库里可区分；</li>
     *   <li>{@code >0} = 命中，前端提示用户「检测到 N 处疑似敏感信息」。</li>
     * </ul>
     *
     * <p>为什么用 {@code INT} 而非 {@code BOOLEAN}：命中多处与一处对用户的信息量不同
     * （「1 处」可以逐个核对，「20 处」应当整体挪走），且聚合时能直接统计分布。</p>
     */
    private Integer sensitiveScanHitCount;

    /**
     * `MA-8` 挂点④：命中规则的稳定 code（逗号分隔）。
     *
     * <p>只存 {@code SensitiveScanRule.code()}，<b>不存原文</b>——与事件 payload 同一口径，
     * 避免附件表变成第二个泄露源。</p>
     */
    private String sensitiveScanRules;
}
