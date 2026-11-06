package com.helloai.core.planner.support;

import com.helloai.core.planner.search.WebSearchOutcome;

import java.util.List;
import java.util.Locale;

/**
 * 对话轮过程步骤文本（澄清链路与助理链路共用同一口径）。
 *
 * <p>为什么要抽出来：两条链路的流式轮都要把「正在联网检索 xxx」「已检索 N 条 · xx.xs」
 * 作为 {@code progress} 帧推给前端、并随消息 payload 落库。若各自复制一份，
 * 文案与耗时格式会随迭代漂移，用户在不同入口看到的过程区语义不一致。</p>
 */
public final class RoundProgressText {

    /** 过程步骤里检索词的展示上限（防止把整段用户消息塞进过程区）。 */
    private static final int PROGRESS_TEXT_LIMIT = 60;

    private RoundProgressText() {
    }

    /**
     * 检索发起步骤：展示<b>真实候选词</b>（`ATT-8`）。
     *
     * <p>改造前这里直接贴用户原话——「正在联网检索：帮我分析一下我上传的附件这个手册
     * 方案是否正确」既不是检索词，也不像进度。候选词为空时只提示正在检索，
     * 不拿原话充数（执行阶段仍可能因 URL 直取而拿到资料）。</p>
     */
    public static String searching(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return "正在联网检索";
        }
        return "正在联网检索：" + abbreviate(String.join(" / ", queries), PROGRESS_TEXT_LIMIT);
    }

    /**
     * 检索结果 → 一行过程步骤文本（前端过程区展示用）。
     *
     * <p>对齐 DeepSeek「已搜索 N 个网页」/ ChatGPT「Searching the web」：让用户知道
     * 「搜了什么词、拿到几条、花了多久」，而不仅仅是一个转圈。</p>
     */
    public static String searchResult(WebSearchOutcome outcome) {
        if (outcome == null) {
            // 搜索未执行（开关关闭）、调用被短路，或候选词全是元词被过滤：
            // 措辞用「未返回结果」而非「未执行」，因为调用方无法区分二者。
            // 结尾用「本轮可用资料」而不是「模型自有知识」——有附件时答案来自附件，
            // 说成"自有知识"会让用户误以为附件没被读到。
            return "联网检索未返回结果（将基于本轮可用资料回答）";
        }
        if (outcome.isFailed()) {
            return "联网检索失败，已降级为模型自有知识回答";
        }
        String queries = outcome.getQueries() == null || outcome.getQueries().isEmpty()
                ? "" : "（检索词：" + String.join(" / ", outcome.getQueries()) + "）";
        String cards = outcome.getModalCards() == null || outcome.getModalCards().isEmpty()
                ? "" : "，含 " + outcome.getModalCards().size() + " 条结构化数据";
        return "已检索 " + outcome.getTotal() + " 条结果" + cards + queries
                + "，耗时 " + String.format(Locale.ROOT, "%.1fs", outcome.getCostMs() / 1000.0);
    }

    /** 过程步骤文本截断（防止把整段用户消息塞进过程区）。 */
    public static String abbreviate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= limit ? oneLine : oneLine.substring(0, limit) + "…";
    }
}
