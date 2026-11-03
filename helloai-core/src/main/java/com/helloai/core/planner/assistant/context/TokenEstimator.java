package com.helloai.core.planner.assistant.context;

import java.util.List;

/**
 * 上下文 token 估算（PA-12，纯函数，无 IO/DB）。
 *
 * <p>公式：{@code overhead + ceil(字符数 / charsPerToken)}。中文约 1.5~2 字符/token、
 * 英文约 3~4 字符/token，{@code charsPerToken} 可按模型校准。用于压缩前的**预测**；
 * 模型返回的 {@code tokenUsage} 可作为事后校准。</p>
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * 估算单段文本 token。
     *
     * @param text          文本（null/空 ⇒ 仅返回固定开销）
     * @param charsPerToken 每 token 字符数（≤0 时回退 4.0）
     * @param overhead      该段固定开销（如每条消息模板，负数按 0）
     */
    public static long estimateText(String text, double charsPerToken, int overhead) {
        int safeOverhead = Math.max(0, overhead);
        if (text == null || text.isEmpty()) {
            return safeOverhead;
        }
        double ratio = charsPerToken > 0 ? charsPerToken : 4.0;
        return safeOverhead + (long) Math.ceil(text.length() / ratio);
    }

    /** 估算一组消息内容的总 token（每条叠加固定开销）。 */
    public static long estimateMessages(List<String> contents, double charsPerToken, int messageOverhead) {
        if (contents == null || contents.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (String content : contents) {
            total += estimateText(content, charsPerToken, messageOverhead);
        }
        return total;
    }
}