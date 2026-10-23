package com.helloai.core.knowledge;

import com.helloai.common.base.BizException;
import com.helloai.common.config.KbProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库（RAG）编排门面（REF-4.2 摄入/检索 + REF-4.1 注入预算）。
 *
 * <p>职责：文本分片 → 嵌入 → 落库（ingest）；查询嵌入 → 向量检索 → 预算截断（search）；
 * 「无 KB ⇒ 摘工具」判据（hasKnowledge）。内置 hard rule：总开关 {@code helloai.kb.enabled}
 * 为 {@code false} 时 ingest / search 一律显式拒绝（REF-6.13：新能力默认关闭）。</p>
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private final KnowledgeEmbedder embedder;
    private final KnowledgeRepository repository;
    private final KbProperties kbProperties;

    public KnowledgeBaseService(KnowledgeEmbedder embedder,
                                KnowledgeRepository repository,
                                KbProperties kbProperties) {
        this.embedder = embedder;
        this.repository = repository;
        this.kbProperties = kbProperties;
    }

    /** 无 KB 判据（REF-4.1）：总开关开 且 库内已有分片。 */
    public boolean hasKnowledge() {
        return kbProperties.isEnabled() && repository.count() > 0;
    }

    /** 摄入一段文本：分片 → 嵌入 → 落库（同 source 幂等覆盖）。返回分片数。 */
    public int ingest(String source, String text) {
        return ingest(source, text, null, null);
    }

    /**
     * 摄入一段文本，带会话 / 用户归属（`ATT-3`：会话附件 ingest）。
     *
     * <p>归属列是检索隔离的唯一依据（V122）——不填的会话附件会被别的会话检索到。
     * 全库知识（运维手册等）传 null。</p>
     *
     * @param conversationId 会话 id；全库知识传 null
     * @param userId         上传者 id；匿名/历史数据可为 null
     */
    public int ingest(String source, String text, Long conversationId, Long userId) {
        if (!kbProperties.isEnabled()) {
            throw new BizException("知识库未启用：helloai.kb.enabled=false");
        }
        if (source == null || source.isBlank()) {
            throw new BizException("知识库摄入：source 不能为空");
        }
        List<String> chunks = chunk(text);
        if (chunks.isEmpty()) {
            return 0;
        }
        List<double[]> vectors = embedder.embed(chunks);
        // 幂等重摄：先清同 source 旧分片再入新
        repository.clearSource(source);
        for (int i = 0; i < chunks.size(); i++) {
            repository.insertChunk(source, i, chunks.get(i), vectors.get(i), conversationId, userId);
        }
        return chunks.size();
    }

    /** 清空某来源的分片（回滚 / 重摄前置；派生数据硬删）。 */
    public void clearSource(String source) {
        repository.clearSource(source);
    }

    /** 按来源 + 分片键取单个分片（引用溯源卡片，REF-4.3）。空可选。 */
    public java.util.Optional<KnowledgeHit> chunkByKey(String source, int chunkIdx) {
        if (!kbProperties.isEnabled()) {
            throw new BizException("知识库未启用：helloai.kb.enabled=false");
        }
        return repository.findBySourceIdx(source, chunkIdx);
    }

    /**
     * 全库检索（对外契约）：KB 未启用时**显式拒绝**。
     *
     * <p>{@code search_knowledge} 工具与管理端走这个入口——开关关着却有调用，说明调用方
     * 判断有误（工具本该被摘除），抛异常比静默返回空更容易发现问题。</p>
     */
    public KnowledgeSearchResult search(String query, Integer maxResults) {
        if (!kbProperties.isEnabled()) {
            throw new BizException("知识库未启用：helloai.kb.enabled=false");
        }
        return doSearch(query, maxResults, null, null, null, null);
    }

    /**
     * 带作用域的检索（`ATT-3`）：会话附件检索**必须**带 {@code conversationId}，
     * 否则会命中别的会话的附件正文（`kb_chunk` 是全平台共享表）。
     *
     * <p><b>与全库入口的关键差异：KB 未启用、嵌入失败都降级为空结果，不抛异常。</b>
     * 调用方是 Prompt 渲染（主链路）——检索只是上下文的一块附加值，它失败应当表现为
     * 「这次没检索到」，而不是「这次请求失败」。两个入口行为不同是刻意的：
     * 对外契约要显式暴露配置错误，对内渲染要保证可用性。</p>
     *
     * @param conversationId 会话 id；null = 全库
     * @param userId         上传者 id；null = 不限（附件可能由多人上传）
     */
    public KnowledgeSearchResult search(String query, Integer maxResults, Long conversationId, Long userId) {
        return search(query, maxResults, conversationId, userId, null);
    }

    /**
     * 带作用域 + 自定义注入预算的检索（`ATT-3`）：会话附件用独立预算（窗口 15%），
     * 不复用全局 {@code KbProperties.charBudget}——后者默认 4,000 字，是为全库检索
     * （{@code search_knowledge} 工具）设的，用来装几万字附件的召回片段远远不够。
     *
     * @param charBudgetOverride 注入内容字符预算；null = 用全局默认
     */
    public KnowledgeSearchResult search(String query, Integer maxResults, Long conversationId, Long userId,
                                        Integer charBudgetOverride) {
        return search(query, maxResults, conversationId, userId, charBudgetOverride, null);
    }

    /**
     * 带作用域 + 自定义预算 + **附件粒度**的检索（`ATT-3`）。
     *
     * <p>{@code sources} 非空时只在这些来源里检索——解决「会话里两份附件、只问第二份，
     * 第一份的分片也被召回」的问题。传 null/空表示不限（会话级）。</p>
     *
     * @param sources 允许命中的 kb 来源（形如 {@code chat:{conv}:{att}}）；null = 全会话
     */
    public KnowledgeSearchResult search(String query, Integer maxResults, Long conversationId, Long userId,
                                        Integer charBudgetOverride, List<String> sources) {
        if (!kbProperties.isEnabled()) {
            return KnowledgeSearchResult.empty(query);
        }
        try {
            return doSearch(query, maxResults, conversationId, userId, charBudgetOverride, sources);
        } catch (RuntimeException e) {
            log.warn("知识库检索失败（降级为无检索结果）: query={}, conversationId={}, sources={}, err={}",
                    query, conversationId, sources, e.toString());
            return KnowledgeSearchResult.empty(query);
        }
    }

    /** 检索主体：嵌入 → 余弦 top-k → 按 char_budget 截断。 */
    private KnowledgeSearchResult doSearch(String query, Integer maxResults,
                                           Long conversationId, Long userId, Integer charBudgetOverride,
                                           List<String> sources) {
        if (query == null || query.isBlank()) {
            return KnowledgeSearchResult.empty(query);
        }
        double[] queryVec = embedder.embedOne(query);
        // 显式给了预算的调用方（会话附件）由自己决定条数：预算 9,600 字配 16 片才填得满，
        // 再走 resolveLimit 会被 KbProperties.maxResults（默认 8）卡回 8 片——预算只用一半。
        // 这里只保留一个硬上限防误传（LIMIT 1000 会拖垮 SQL）。
        int k = (charBudgetOverride != null && charBudgetOverride > 0)
                ? Math.min(maxResults == null || maxResults < 1 ? kbProperties.getDefaultResults() : maxResults,
                           HARD_MAX_RESULTS)
                : resolveLimit(maxResults);
        List<KnowledgeHit> hits = repository.search(queryVec, k, conversationId, userId, sources);

        int budget = charBudgetOverride != null && charBudgetOverride > 0
                ? charBudgetOverride : kbProperties.getCharBudget();
        int used = 0;
        boolean truncated = false;
        List<KnowledgeSearchResult.Hit> out = new ArrayList<>();
        for (KnowledgeHit hit : hits) {
            int remaining = budget - used;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            String content = hit.content();
            if (content.length() > remaining) {
                content = KbCharBudget.truncate(content, remaining);
                truncated = true;
            }
            out.add(new KnowledgeSearchResult.Hit(hit.source(), hit.chunkIdx(), content));
            used += content.length();
        }
        return new KnowledgeSearchResult(query, out, used, truncated);
    }

    /** 显式预算场景的条数硬上限：防误传大值拖垮 SQL LIMIT。 */
    private static final int HARD_MAX_RESULTS = 64;

    private int resolveLimit(Integer maxResults) {
        if (maxResults == null || maxResults < 1) {
            return kbProperties.getDefaultResults();
        }
        return Math.min(maxResults, kbProperties.getMaxResults());
    }

    private List<String> chunk(String text) {
        String normalized = text == null ? "" : text;
        int size = Math.max(1, kbProperties.getChunkSize());
        int overlap = Math.min(Math.max(0, kbProperties.getChunkOverlap()), size - 1);
        int step = Math.max(1, size - overlap);
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < normalized.length()) {
            chunks.add(normalized.substring(i, Math.min(normalized.length(), i + size)));
            if (i + size >= normalized.length()) {
                break;
            }
            i += step;
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }
}