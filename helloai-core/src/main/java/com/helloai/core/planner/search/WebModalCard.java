package com.helloai.core.planner.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商返回的结构化模态卡（垂直领域精确数据，W-7）。
 *
 * <p>博查 AI Search 在网页结果之外，会按查询语义动态返回垂域结构化卡（天气 / 百科 /
 * 股票 / 汇率 / 金价 / 油价 / 万年历 / 火车 / 星座 / 手机 / 汽车 等），数据来自垂域
 * 数据源而非网页抽取——比网页摘要更精确（如「今日金价」的实时数字）。此前
 * {@code AiSearchWebSearchServiceImpl.parseResponse} 只消费 {@code content_type=webpage}
 * 的 source，模态卡被整体丢弃。</p>
 *
 * <p><b>为什么不复用 {@link WebSearchResult}</b>：模态卡没有 url（不是网页），语义上是
 * 「结构化事实」而不是「待溯源的信源」；混进 {@code results} 会污染 URL 去重口径与
 * 条数上限（{@code maxResults}），也会让前端把它当网页链接渲染。故独立记录类型，
 * 由 {@code WebSearchOutcome.modalCards} 单独承载（payload 可查验、Prompt 单独成节）。</p>
 *
 * <p>构造器契约与 {@link WebSearchResult} 同款：Jackson 反序列化需显式
 * {@code @NoArgsConstructor + @AllArgsConstructor}（工具输出 JSON 经编排器裸 ObjectMapper 解析）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebModalCard {

    /** 供应商卡类型标识（如 {@code weather_china} / {@code baike_pro} / {@code stock}）。 */
    private String type;

    /** 卡标题（从卡内容启发式提取的标题字段；提取不到时回退可读类型名）。 */
    private String title;

    /**
     * 卡内容摘要（结构化数据的紧凑文本，已截断到
     * {@link com.helloai.common.config.WebSearchProperties#getMaxSnippetChars()} 字符内）。
     */
    private String summary;
}
