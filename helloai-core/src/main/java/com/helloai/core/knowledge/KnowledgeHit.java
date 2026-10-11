package com.helloai.core.knowledge;

/**
 * 知识库分片检索命中（存储层 / 服务层内部往返对象）。
 *
 * @param id       分片主键
 * @param source   来源标识（文档 / 技能包 / 任务产出等）
 * @param chunkIdx 分片序号（来源内从 0 起）
 * @param content  分片正文
 * @param distance 与查询向量的余弦距离（越小越相关）
 */
public record KnowledgeHit(Long id, String source, int chunkIdx, String content, double distance) {
}