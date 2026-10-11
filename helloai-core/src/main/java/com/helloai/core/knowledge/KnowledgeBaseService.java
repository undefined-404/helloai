package com.helloai.core.knowledge;

import com.helloai.common.base.BizException;
import com.helloai.common.config.KbProperties;
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
            repository.insertChunk(source, i, chunks.get(i), vectors.get(i));
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

    /** 检索：query 嵌入 → 余弦 top-k → 按 char_budget 截断注入内容。 */
    public KnowledgeSearchResult search(String query, Integer maxResults) {
        if (!kbProperties.isEnabled()) {
            throw new BizException("知识库未启用：helloai.kb.enabled=false");
        }
        if (query == null || query.isBlank()) {
            return KnowledgeSearchResult.empty(query);
        }
        double[] queryVec = embedder.embedOne(query);
        int k = resolveLimit(maxResults);
        List<KnowledgeHit> hits = repository.search(queryVec, k);

        int budget = kbProperties.getCharBudget();
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