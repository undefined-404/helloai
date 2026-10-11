package com.helloai.core.knowledge;

import java.util.List;

/**
 * `search_knowledge` 工具对模型的返回（REF-4.2 检索 + REF-4.1 char_budget 预算截断后）。
 *
 * @param query      原始查询
 * @param hits       命中分片（已按预算截断内容）
 * @param totalChars 注入模型上下文的 KB 内容总字符数（≤ char_budget）
 * @param truncated  是否因预算被截断（消费方应提示「结果被截断」）
 */
public record KnowledgeSearchResult(
        String query,
        List<Hit> hits,
        int totalChars,
        boolean truncated) {

    public static KnowledgeSearchResult empty(String query) {
        return new KnowledgeSearchResult(query, List.of(), 0, false);
    }

    /** 单个命中分片。 */
    public record Hit(String source, int chunkIdx, String content) {
    }
}