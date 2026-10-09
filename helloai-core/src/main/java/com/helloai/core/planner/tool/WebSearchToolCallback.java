package com.helloai.core.planner.tool;

import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.planner.service.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

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
 * <p><b>REF-1.3「条件可用」的首个真实消费者</b>：{@link #toolAvailability()} 声明
 * 「{@link WebSearchService#isAvailable()} 为 false ⇒ {@code web_search} 从模型可见列表摘除」。
 * 三件事必须分清，不得互相代替：</p>
 * <ul>
 *   <li><b>能力具备</b>（本声明）：总开关 + 当前 provider 凭据是否就绪——进程内事实，不落库；</li>
 *   <li><b>会话业务开关</b>（{@code RequirementConversation.webSearchEnabled}）：本次要不要搜、
 *       结果是否注入澄清 prompt——业务决策，属 planner 会话侧；</li>
 *   <li><b>Agent 授权</b>（{@code agent_mcp_server}）：某 Agent 是否被授权使用本工具——绑定层事实。</li>
 * </ul>
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

    /**
     * 「按条件可用」声明（REF-1.3）：无搜索凭据（或总开关关闭）⇒ {@code web_search}
     * 从模型可见工具列表摘除，避免模型调用一个必然返回空结果、事后无从判断「是没搜到
     * 还是搜不了」的工具。
     *
     * <p>判定<b>委托能力所有者</b>（{@link WebSearchService#isAvailable()}），
     * 不在工具侧重算三家供应商的密钥口径（必然与 Router 漂移，见该接口 javadoc）。
     * 与上下文无关（不依赖 {@link ToolContext} 的任何字段），故天然满足 fail-open 契约
     * ——「不知道」不影响判定，判定只取决于进程内的凭据事实。</p>
     *
     * <p>摘除<b>不落库、不改管理面、不改 MCP {@code tools/list} 暴露面</b>：
     * 外部 MCP Agent 依旧看得到本工具，调用时走既有的「空结果优雅降级」兜底；
     * 本语义位作用于<b>进程内 Runtime 的模型可见列表</b>（内部 LLM 执行者）。</p>
     */
    @Override
    public Map<String, Predicate<ToolContext>> toolAvailability() {
        return Map.of("web_search", context -> webSearchService.isAvailable());
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