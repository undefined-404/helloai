package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 联网搜索配置（需求澄清首轮 LLM 调用前预检索行业资料注入提示词）。
 *
 * <p>作为 <code>RequirementClarifyService</code> 首轮增强的可选外部依赖集中管理，
 * 仿 {@link DoorbellProperties} 风格，避免供应商、API Key、超时等散落在业务代码里。</p>
 *
 * <p>当前仅在"按会话级开关开启"且"首轮对话"时触发，预检索行业资料 / 竞品 / 技术方案后
 * 注入 <code>{{WEB_SEARCH_CONTEXT}}</code> 占位符；失败一律降级跳过，不阻断澄清流程。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.web-search")
public class WebSearchProperties {

    /** 总开关。false 时链路完全短路（不调任何下游），方便回归纯对话模式。 */
    private boolean enabled = true;

    /**
     * 供应商选择：bocha（默认，国内）/ tavily（境外）。
     * 切换时不存在的 Bean 自动跳过，避免启动期 fail-fast 阻塞。
     */
    private String provider = "bocha";

    /** 单次搜索请求超时（毫秒）。默认 3 秒，搜索是辅助增强，体验优先。 */
    private long timeoutMs = 3_000L;

    /** 每次搜索最多返回的条目数。默认 5 条，每条截断到 {@link #maxSnippetChars} 字符。 */
    private int maxResults = 5;

    /** 单条 snippet 最大字符数。超出截断，防止注入占位符后总长爆 token。 */
    private int maxSnippetChars = 200;

    /** 关键词提取长度上限：首条用户消息前 N 字作为查询。规则提取，不额外调 LLM。 */
    private int queryKeywordLimit = 40;

    /** 博查 Web Search API 端点。 */
    private String bochaBaseUrl = "https://api.bochaai.com/v1/web-search";

    /** 博查 API Key（建议通过 env BOCHA_API_KEY 注入；空字符串=该供应商未启用）。 */
    private String bochaApiKey = "";

    /** Tavily Search API 端点。 */
    private String tavilyBaseUrl = "https://api.tavily.com/search";

    /** Tavily API Key（建议通过 env TAVILY_API_KEY 注入；空字符串=该供应商未启用）。 */
    private String tavilyApiKey = "";
}
