package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 联网搜索配置（需求对话每轮 LLM 调用前预检索行业资料注入提示词）。
 *
 * <p>作为 <code>RequirementClarifyService</code> 对话轮次增强的可选外部依赖集中管理，
 * 仿 {@link DoorbellProperties} 风格，避免供应商、API Key、超时等散落在业务代码里。</p>
 *
 * <p>V45 起任意对话模式（CHAT/CLARIFY）每轮 LLM 调用前按会话级开关触发，
 * 预检索行业资料 / 竞品 / 技术方案后注入 <code>{{WEB_SEARCH_CONTEXT}}</code> 占位符；
 * 失败一律降级跳过，不阻断对话流程。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.web-search")
public class WebSearchProperties {

    /** 总开关。false 时链路完全短路（不调任何下游），方便回归纯对话模式。 */
    private boolean enabled = true;

    /**
     * 供应商选择：bocha（默认，国内）/ tavily（境外）/ deepseek-native
     * （DeepSeek 原生 web_search 服务端工具，当"搜索引擎"用）。
     * 切换时不存在的 Bean 自动跳过，避免启动期 fail-fast 阻塞。
     */
    private String provider = "bocha";

    /**
     * 单次搜索请求超时（毫秒）。默认 8 秒：博查开启 AI 摘要（summary=true）时耗时波动大，
     * 原 3 秒易静默超时降级为空列表（V44 从 3s 上调）；搜索是辅助增强，体验优先。
     */
    private long timeoutMs = 8_000L;

    /** 每次搜索最多返回的条目数。默认 5 条，每条截断到 {@link #maxSnippetChars} 字符。 */
    private int maxResults = 5;

    /** 单条 snippet 最大字符数。超出截断，防止注入占位符后总长爆 token。 */
    private int maxSnippetChars = 200;

    /** 关键词提取长度上限：首条用户消息前 N 字作为查询。规则提取，不额外调 LLM。 */
    private int queryKeywordLimit = 40;

    /** 博查 Web Search API 端点。 */
    private String bochaBaseUrl = "https://api.bochaai.com/v1/web-search";

    /** 博查 API Key（env BOCHA_API_KEY 注入；未配置/空字符串=该供应商未启用）。 */
    private String bochaApiKey = "${BOCHA_API_KEY:}";

    /** Tavily Search API 端点。 */
    private String tavilyBaseUrl = "https://api.tavily.com/search";

    /** Tavily API Key（建议通过 env TAVILY_API_KEY 注入；空字符串=该供应商未启用）。 */
    private String tavilyApiKey = "";

    /** DeepSeek 原生联网搜索（Anthropic 兼容端点 + web_search_20250305 服务端工具）端点。 */
    private String deepseekBaseUrl = "https://api.deepseek.com/anthropic/v1/messages";

    /** DeepSeek API Key（env DEEPSEEK_API_KEY 注入；未配置/空字符串=该供应商未启用）。 */
    private String deepseekApiKey = "";

    /** DeepSeek 原生搜索使用的模型（单次调用仅取结构化搜索结果块，正文为副产品）。 */
    private String deepseekModel = "deepseek-chat";

    /** DeepSeek 原生搜索生成 token 上限（搜索结果块本身不大，压低以控成本）。 */
    private int deepseekMaxTokens = 1024;

    /**
     * DeepSeek 原生搜索请求超时（毫秒）。独立于 {@link #timeoutMs}：该路径是一次完整
     * LLM 调用（服务端检索 + 生成），耗时显著高于普通搜索 API，默认 15 秒。
     */
    private long deepseekTimeoutMs = 15_000L;

    /**
     * 用户消息 URL 直取开关（V43）：消息含 http(s) 链接时直接访问抓取页面正文
     * 注入上下文，而非把裸 URL 文本当搜索词。false 时回退纯搜索引擎行为。
     */
    private boolean urlFetchEnabled = true;

    /** 单个网页抓取超时（毫秒）。 */
    private long urlFetchTimeoutMs = 8_000L;

    /** 单轮最多直取的页面数（控成本，取消息中前 N 个 URL）。 */
    private int urlFetchMaxPages = 2;

    /** 单页抓取正文注入上下文的最大字符数（超出截断，防 token 爆炸）。 */
    private int urlFetchMaxTextChars = 4_000;
}
