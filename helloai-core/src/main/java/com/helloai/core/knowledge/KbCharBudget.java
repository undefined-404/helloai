package com.helloai.core.knowledge;

import com.helloai.core.shared.util.TextTruncator;

/**
 * 知识库注入预算工具（REF-4.1「char_budget 契约」）。
 *
 * <p>检索结果注入模型上下文前，按预算做**行边界截断**（复用 {@link TextTruncator}，
 * 与既有依赖产出截断同一算法，避免两套截断口径）。预算值来自
 * {@link com.helloai.common.config.KbProperties#getCharBudget()}。</p>
 */
public final class KbCharBudget {

    private KbCharBudget() {
    }

    /**
     * 把单个分片正文截到不超过 {@code budget} 字符（行边界），超限返回截断后文本。
     */
    public static String truncate(String content, int budget) {
        if (content == null || content.length() <= budget) {
            return content;
        }
        return TextTruncator.truncateAtLineBoundary(content, budget);
    }
}