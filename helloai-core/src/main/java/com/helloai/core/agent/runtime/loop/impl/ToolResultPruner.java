package com.helloai.core.agent.runtime.loop.impl;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果剪枝（REF-8.2.3，对齐 DSH `compaction-tool-result-pruner`）。
 *
 * <p><b>为什么是"剪枝"而不是"摘要"</b>：压缩链的第一道必须是**确定性**的——纯文本裁剪、
 * 不调模型、不产生失真、且**留可审计标记**（标记行明示"此处省略 N 字符"）。标杆的顺序
 * 即「先剪工具结果，再选摘要」（`docs/agent-lifecycle.zh.md`）。</p>
 *
 * <p><b>口径逐字对齐标杆</b>（`packages/compaction/compaction-tool-result-pruner/src/config.ts`）：
 * 阈值 {@code thresholdChars=8192}、头 {@code headChars=4096}、尾 {@code tailChars=1024}；
 * 标记文案 {@code PRUNE_MARKER} 原样英文；**按 Unicode 码点计长**（`codePointLength`，
 * 避免中文/emoji 按字节或 UTF-16 单元误判，也避免切断代理对）。</p>
 */
final class ToolResultPruner {

    /** 固定标记：替换被移除的中段（与 DSH `PRUNE_MARKER` 逐字一致）。 */
    static final String PRUNE_MARKER = "\n\n[... tool result middle pruned ...]\n\n";

    private ToolResultPruner() {
    }

    /** 按 Unicode 码点计长（不切分代理对）。 */
    static int codePointLength(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /**
     * 剪枝单条文本：未超阈值原样返回；超阈值则「头 {@code headChars} 码点 + 标记 + 尾 {@code tailChars} 码点」。
     */
    static String prune(String content, int thresholdChars, int headChars, int tailChars) {
        if (content == null) {
            return null;
        }
        int length = codePointLength(content);
        if (length <= thresholdChars || thresholdChars <= 0) {
            return content;
        }
        int head = Math.max(0, Math.min(headChars, length));
        int tail = Math.max(0, Math.min(tailChars, length - head));
        int headEnd = content.offsetByCodePoints(0, head);
        int tailStart = tail == 0
                ? content.length()
                : content.offsetByCodePoints(0, length - tail);
        if (tailStart < headEnd) {
            tailStart = headEnd;
        }
        return content.substring(0, headEnd) + PRUNE_MARKER + content.substring(tailStart);
    }

    /**
     * 就地对消息列表里的工具结果做剪枝。
     *
     * <p>只改 {@link ToolResponseMessage} 的 {@code responseData}（其余消息类型逐字不动），
     * 返回被改写的消息条数（0 表示无变化，调用方据此判断是否需要替换列表）。</p>
     */
    static int pruneMessages(List<Message> messages, int thresholdChars, int headChars, int tailChars) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int rewritten = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = toolMessage.getResponses();
            if (responses == null || responses.isEmpty()) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> pruned = new ArrayList<>(responses.size());
            boolean changed = false;
            for (ToolResponseMessage.ToolResponse response : responses) {
                String original = response.responseData();
                String result = prune(original, thresholdChars, headChars, tailChars);
                if (result != null && !result.equals(original)) {
                    changed = true;
                }
                pruned.add(new ToolResponseMessage.ToolResponse(
                        response.id(), response.name(), result == null ? "" : result));
            }
            if (changed) {
                messages.set(i, ToolResponseMessage.builder().responses(pruned).build());
                rewritten++;
            }
        }
        return rewritten;
    }
}
