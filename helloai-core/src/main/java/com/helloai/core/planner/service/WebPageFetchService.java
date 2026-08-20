package com.helloai.core.planner.service;

import com.helloai.core.planner.search.WebPageContent;

/**
 * 网页直取服务接口：直接访问用户消息中给出的 URL 并抓取页面正文。
 *
 * <p>实现类：{@link WebPageFetchServiceImpl}。与搜索引擎互补——用户明确给出站点时，
 * 第一手页面内容优于搜索引擎的间接结果；抓取正文注入 Prompt，让 LLM 基于真实站点
 * 资料作答，而非把裸 URL 文本当搜索词（搜索引擎对 URL 文本检索效果极差）。</p>
 *
 * <p>失败语义：实现必须捕获所有异常返回 ok=false 记录，<b>不</b>抛出。
 * 目的：抓取是辅助增强，失败时不能阻断需求澄清主流程。</p>
 */
public interface WebPageFetchService {

    /**
     * 抓取单个页面。
     *
     * @param url 待访问的 http(s) 链接
     * @return 抓取记录（成功含 title/text；失败 ok=false + reason，绝不抛异常、绝不返回 null）
     */
    WebPageContent fetch(String url);
}
