package com.helloai.api.dto.admin;

/**
 * 知识库分片视图（引用溯源弹窗，`REF-4.3`）。
 *
 * <p>{@code fileName} 只对会话附件来源（{@code chat:{conversationId}:{attachmentId}}）有值：
 * 前端弹窗标题要显示「面试资料.docx · 第 37 片」，而不是把
 * {@code chat:2113486948265484290:2113623035980943361#36} 这种内部标识直接摆给用户看。</p>
 *
 * @param source   来源标识（与 {@code kb_chunk.source} 一致）
 * @param chunkIdx 分片序号（0-based，与锚点原文一致）
 * @param content  分片正文
 * @param fileName 附件原始文件名；非会话附件来源为 null
 */
public record KbChunkView(String source, int chunkIdx, String content, String fileName) {
}
