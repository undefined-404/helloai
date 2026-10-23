package com.helloai.core.knowledge;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识库分片存储 / 检索（REF-4.2）。PG 单后端：直接经 {@link JdbcTemplate} 使用
 * `vb106` 已启用的 pgvector `vector` 扩展（自建 SQL，不引入 spring-ai vectorstore
 * 侧库依赖，PG 单后端与 ADR-002 §7「向量与业务同库同事务」贯通）。
 *
 * <p>维度 = {@code vector(1024)}（ADR-002 单向门，上线即冻结）。相似度 = 余弦
 * （HNSW vector_cosine_ops，`<=>`），对小规模知识库检索质量稳健。</p>
 */
@Repository
public class KnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 已有分片数（无 KB ⇒ search_knowledge 摘除，REF-4.1）。 */
    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_chunk WHERE deleted = 0", Long.class);
        return c == null ? 0 : c;
    }

    /** 摄入一个分片（全库知识，无会话归属）。 */
    public void insertChunk(String source, int chunkIdx, String content, double[] embedding) {
        insertChunk(source, chunkIdx, content, embedding, null, null);
    }

    /**
     * 摄入一个分片，带会话 / 用户归属（`ATT-3`：会话附件 ingest 到 kb）。
     *
     * <p>归属列是可空的：全库知识（运维手册等）不填，会话附件必填——否则检索侧
     * 无法把它们与别的会话隔离。</p>
     *
     * @param conversationId 会话 id；非会话知识传 null
     * @param userId         上传者 id；历史数据可为 null（检索侧按 IS NULL 兼容）
     */
    public void insertChunk(String source, int chunkIdx, String content, double[] embedding,
                            Long conversationId, Long userId) {
        jdbcTemplate.update(
                "INSERT INTO kb_chunk(id, source, chunk_idx, content, embedding, "
                        + "conversation_id, user_id, create_by, update_by) "
                        + "VALUES (?, ?, ?, ?, ?::vector, ?, ?, 'system', 'system')",
                IdWorker.getId(), source, chunkIdx, content, toVectorStr(embedding),
                conversationId, userId);
    }

    /** 按来源 + 分片键取单个分片（引用溯源卡片，REF-4.3）。空可选。 */
    public java.util.Optional<KnowledgeHit> findBySourceIdx(String source, int chunkIdx) {
        List<KnowledgeHit> found = jdbcTemplate.query(
                "SELECT id, source, chunk_idx, content, 0 AS distance "
                        + "FROM kb_chunk WHERE source = ? AND chunk_idx = ? AND deleted = 0 LIMIT 1",
                (rs, rowNum) -> new KnowledgeHit(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getInt("chunk_idx"),
                        rs.getString("content"),
                        rs.getDouble("distance")),
                source, chunkIdx);
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    /** 清空某来源的所有分片（重摄 / 回滚用；派生数据硬删，不进业务软删）。 */
    public void clearSource(String source) {
        jdbcTemplate.update("DELETE FROM kb_chunk WHERE source = ?", source);
    }

    /**
     * 检索：query 向量 → 余弦最近 top-k 分片（全库，无作用域）。
     *
     * <p>供 {@code search_knowledge} 工具与全库知识检索使用，行为与改造前一致。</p>
     */
    public List<KnowledgeHit> search(double[] queryVec, int limit) {
        return search(queryVec, limit, null, null);
    }

    /**
     * 带作用域的检索（`ATT-3`）：会话附件检索**必须**走这里，不能走无参版本。
     *
     * <p><b>为什么必须带 conversationId</b>：{@code kb_chunk} 是**全平台共享**的向量表，
     * 不带会话过滤时 A 会话能检索出 B 会话的附件正文——跨会话泄漏。{@code source} 前缀
     * （{@code chat:{conv}:{att}}）只是命名约定，不是访问控制。</p>
     *
     * <p><b>SQL 用 {@code ? IS NULL OR} 而非动态拼串</b>：保持参数绑定（防注入），
     * 同时让「不限作用域」与「限定作用域」共用一条语句与一份执行计划。</p>
     *
     * <p><b>user_id 的 IS NULL 分支</b>：V122 之前写入的历史数据没有归属，若严格按
     * {@code user_id = ?} 过滤会把它们排除；单租户阶段历史上不存在跨用户数据，故放行。</p>
     *
     * @param conversationId 会话 id；null = 不限（全库）
     * @param userId         上传者 id；null = 不限
     */
    public List<KnowledgeHit> search(double[] queryVec, int limit, Long conversationId, Long userId) {
        return search(queryVec, limit, conversationId, userId, null);
    }

    /**
     * 带**附件粒度**过滤的检索（`ATT-3`）：{@code sources} 非空时只在这些来源里检索。
     *
     * <p><b>为什么需要这一层</b>：会话级过滤（{@code conversation_id}）只能保证「不跨会话」，
     * 保证不了「不跨附件」。同一会话传了两份文件、用户只问第二份时，第一份的分片仍会进
     * 检索池并占用注入预算——表现为「问附件2，回答里混进附件1 的内容」。</p>
     *
     * <p>{@code source} 形如 {@code chat:{conv}:{att}}，由调用方从选中附件的 {@code kb_source}
     * 映射而来；传 null/空表示不限（保留原会话级行为）。</p>
     *
     * <p><b>为什么这里动态拼 IN 而不是沿用 {@code ? IS NULL OR} 模式</b>：来源个数可变
     * （1~5），占位符数量必须跟随；参数仍走绑定，无注入风险。会话/用户过滤继续用
     * {@code ? IS NULL OR} 保持单语句。</p>
     */
    public List<KnowledgeHit> search(double[] queryVec, int limit, Long conversationId, Long userId,
                                     List<String> sources) {
        String vec = toVectorStr(queryVec);
        StringBuilder sql = new StringBuilder(
                "SELECT id, source, chunk_idx, content, (embedding <=> ?::vector) AS distance "
                        + "FROM kb_chunk WHERE deleted = 0 "
                        + "AND (?::bigint IS NULL OR conversation_id = ?::bigint) "
                        + "AND (?::bigint IS NULL OR user_id IS NULL OR user_id = ?::bigint) ");
        List<Object> args = new java.util.ArrayList<>(6);
        args.add(vec);
        args.add(conversationId);
        args.add(conversationId);
        args.add(userId);
        args.add(userId);
        if (sources != null && !sources.isEmpty()) {
            sql.append("AND source IN (");
            for (int i = 0; i < sources.size(); i++) {
                sql.append(i == 0 ? "?" : ", ?");
                args.add(sources.get(i));
            }
            sql.append(") ");
        }
        sql.append("ORDER BY embedding <=> ?::vector LIMIT ?");
        args.add(vec);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new KnowledgeHit(
                        rs.getLong("id"),
                        rs.getString("source"),
                        rs.getInt("chunk_idx"),
                        rs.getString("content"),
                        rs.getDouble("distance")),
                args.toArray());
    }

    private String toVectorStr(double[] v) {
        return "[" + IntStream.range(0, v.length)
                .mapToObj(i -> String.valueOf(v[i]))
                .collect(Collectors.joining(",")) + "]";
    }
}