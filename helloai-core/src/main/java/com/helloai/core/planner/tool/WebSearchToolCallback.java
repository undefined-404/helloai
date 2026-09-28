package com.helloai.core.planner.tool;

import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 联网搜索平台工具（web_search，阶段四 Capability 化）。
 *
 * <p>能力归 planner（§5.3）：把 {@link WebSearchService}（供应商无关 Router）
 * 封装为 spring-ai {@code @Tool}，经 {@link ToolCallbackContributor} 端口并入平台
 * 工具目录（ToolRegistry/ToolExecutor 自动可见，验证脚本工具矩阵含 web_search）。</p>
 *
 * <p>失败语义保持既有契约：搜索失败/异常降级为空结果（不抛异常），消费方按
 * 「空列表降级」处理——webSearchEnabled 会话开关是业务开关（RequirementClarifyService
 * 侧），与能力注册无关。</p>
 *
 * <p>实现不加 {@code @Transactional}/AOP 注解：agent 域收集器要求原始对象
 * （代理会丢 @Tool 注解，见 McpToolConfig 说明）。</p>
 */
@Slf4j
@Component
public class WebSearchToolCallback implements ToolCallbackContributor {

    /** 结果条数上限（@ToolParam 入参 clamp 边界）。 */
    private static final int MAX_RESULTS_LIMIT = 10;
    /** 默认结果条数（@ToolParam 缺省时）。 */
    private static final int DEFAULT_MAX_RESULTS = 3;

    private final WebSearchService webSearchService;

    /** 显式构造器（与主类口径一致，绕开 Lombok 增量编译坑）。 */
    public WebSearchToolCallback(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    @Override
    public Object toolObject() {
        return this;
    }

    @Tool(name = "web_search", description = """
            【何时使用】对话/任务上下文需要外部实时信息时调用：行业资料、竞品动态、技术方案、
            新闻时效内容等；也用于用户消息带 URL 时的站点资料补充检索。
            【调用频率】每次检索一批关键词调用一次；多关键词可多次调用合并结果，按需控制总数。
            【Gotchas】
            - 失败/无结果返回空 results（不抛异常），请在回复中说明未能检索到外部资料
            - maxResults 范围 1-10，超出按边界收敛；withAnswer=true 时供应商支持才附带大模型总结
            - 结果已按供应商归一化：title/url/snippet/siteName 四字段
            【相关工具】无（独立检索工具；结果溯源以 url 为准）
            """)
    public WebSearchToolResult webSearch(
            @ToolParam(description = "搜索关键词", required = true) String query,
            @ToolParam(description = "最大结果条数，范围 1-10，默认 3", required = false) Integer maxResults,
            @ToolParam(description = "是否同时获取供应商大模型总结（answer），默认 false", required = false) Boolean withAnswer) {
        if (query == null || query.isBlank()) {
            log.warn("web_search: 搜索关键词为空，返回空结果");
            return WebSearchToolResult.empty("web_search_tool");
        }
        int limit = maxResults == null || maxResults < 1
                ? DEFAULT_MAX_RESULTS : Math.min(maxResults, MAX_RESULTS_LIMIT);
        try {
            String provider = webSearchService.provider();
            List<WebSearchResult> results = webSearchService.search(query, limit);
            String answer = null;
            if (Boolean.TRUE.equals(withAnswer)) {
                answer = webSearchService.answerSummary(query);
            }
            log.info("web_search 工具调用完成: query={}, maxResults={}, hits={}, withAnswer={}",
                    query, limit, results == null ? 0 : results.size(), withAnswer);
            return new WebSearchToolResult(provider,
                    results == null ? List.of() : results, answer);
        } catch (Exception e) {
            // 契约：搜索失败绝不抛（消费方按空列表降级）；防御性兜底
            log.warn("web_search 工具调用异常，降级空结果: query={}, err={}", query, e.getMessage());
            return WebSearchToolResult.empty("web_search_tool");
        }
    }
}