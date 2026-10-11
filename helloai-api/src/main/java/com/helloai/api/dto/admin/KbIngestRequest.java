package com.helloai.api.dto.admin;

/**
 * 知识库摄入请求（管理端 {@code POST /api/kb/ingest}）。
 *
 * @param source 来源标识（幂等重摄按 source 覆盖）
 * @param text   待分片/嵌入/入库的原始正文
 */
public record KbIngestRequest(String source, String text) {
}