package com.helloai.core.planner.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WebSearchOutcome#toContextText()} Prompt 渲染单测。
 *
 * <p>聚焦 W-7：结构化模态卡节的存在性与顺序（总结 → 结构化数据 → 网页结果），
 * 以及「只有卡无网页」时不被判为空资料。</p>
 */
@DisplayName("WebSearchOutcome 上下文渲染")
class WebSearchOutcomeTest {

    private static WebModalCard card(String type, String title, String summary) {
        return WebModalCard.builder().type(type).title(title).summary(summary).build();
    }

    private static WebSearchResult result(String title, String url, String snippet) {
        return WebSearchResult.builder().title(title).url(url).snippet(snippet).build();
    }

    @Test
    @DisplayName("W-7：只有模态卡（无网页/无 answer）也不判为空资料，渲染结构化数据节")
    void onlyModalCards_rendersStructuredSection() {
        WebSearchOutcome outcome = WebSearchOutcome.builder()
                .modalCards(List.of(card("gold_price", "今日金价", "612.35 元/克，涨 1.2")))
                .build();

        String text = outcome.toContextText();
        assertThat(text).isNotEqualTo("（无可用联网资料）");
        assertThat(text).contains("结构化数据").contains("今日金价").contains("612.35");
        assertThat(text).doesNotContain("联网检索到的行业资料");
    }

    @Test
    @DisplayName("W-7：总结 → 结构化数据 → 网页结果 的渲染顺序稳定")
    void ordering_answerThenCardsThenResults() {
        WebSearchOutcome outcome = WebSearchOutcome.builder()
                .answer("总结内容")
                .modalCards(List.of(card("stock", "上证指数", "收盘 3210.55")))
                .results(List.of(result("行情页", "https://a.com/1", "大盘分析")))
                .build();

        String text = outcome.toContextText();
        int answerIdx = text.indexOf("大模型总结");
        int cardsIdx = text.indexOf("结构化数据");
        int resultsIdx = text.indexOf("联网检索到的行业资料");
        assertThat(answerIdx).isGreaterThanOrEqualTo(0);
        assertThat(cardsIdx).isGreaterThan(answerIdx);
        assertThat(resultsIdx).isGreaterThan(cardsIdx);
    }

    @Test
    @DisplayName("全空（无 answer / 无卡 / 无网页）→ 占位符")
    void allEmpty_placeholder() {
        WebSearchOutcome outcome = WebSearchOutcome.builder().build();
        assertThat(outcome.toContextText()).isEqualTo("（无可用联网资料）");
    }

    @Test
    @DisplayName("W-7：卡标题为空时回退卡类型展示（不出现空标题行）")
    void cardWithoutTitle_fallsBackToType() {
        WebSearchOutcome outcome = WebSearchOutcome.builder()
                .modalCards(List.of(card("weather_china", null, "晴 26℃")))
                .build();

        assertThat(outcome.toContextText()).contains("weather_china").contains("晴 26℃");
    }
}
