package com.helloai.core.planner.service;

import java.util.List;

/**
 * 联网搜索查询规划服务接口（用户语义文本 → 1~N 个候选搜索词）。
 *
 * <p>替代"消息前 40 字原样当搜索词"的原始提取：疑问句式、敬语、标点、多主题长句
 * 在关键词检索引擎（博查/Tavily）上命中率极低。本接口负责把"人话"翻译成搜索关键词：
 * 规则清洗总是执行（零成本），LLM 改写条件触发（规则拆不出多候选词且消息长/含疑问句式），
 * 改写失败一律降级规则结果。</p>
 *
 * <p>失败语义：实现必须捕获所有异常返回规则兜底结果，<b>不</b>抛出——
 * 搜索是辅助增强，改写挂了不能阻断对话主流程。</p>
 */
public interface SearchQueryPlannerService {

    /**
     * 规划候选搜索词（按优先级排序，供调用方顺序降级逐个尝试）。
     *
     * @param semanticText 剥离 URL 后的用户消息语义文本（可为 null/空白）
     * @return 候选搜索词列表（最多 {@code helloai.web-search.max-queries} 条）；
     *         输入空白或无可提取关键词时返回空列表（绝不抛异常）
     */
    List<String> planQueries(String semanticText);
}
