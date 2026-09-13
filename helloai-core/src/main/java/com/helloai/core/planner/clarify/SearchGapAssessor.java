package com.helloai.core.planner.clarify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 检索充分性评估器（Deep Research 风格补搜的决策环节）。
 *
 * <p>首轮搜索完成后，把「用户需求 + 已检索查询词 + 首轮结果标题/摘要」交给平台内 LLM，
 * 判断信息是否已覆盖需求主要方面；若明显不足则产出 1 个补充查询词，交编排器发起补搜。
 * 对齐 DeepSeek 网页版 / Kimi 的「搜索 → 发现缺口 → 再检索」行为。</p>
 *
 * <p><b>LLM 通道</b>：走平台内执行链（{@link PlatformAgentExecutionService#executeSync} +
 * Planner 角色 Agent），复用系统设置页配置的平台级凭证——无需为搜索单独维护第二个 Key，
 * 与澄清主回复同源。失败语义：无可用 Planner / 超时 / 失败 / 解析失败一律返回 null（不补搜），
 * 绝不抛异常——搜索是辅助增强，不阻断澄清主流程。</p>
 */
@Slf4j
@Component
public class SearchGapAssessor {

    /** 缺口评估 AgentTask 的 scene 标识（日志可观测 / 测试 stub 精确匹配）。 */
    public static final String GAP_SCENE = "websearch_gap_assess";

    /** 缺口评估 Prompt 模板（占位符见模板内注释）。 */
    private static final String GAP_PROMPT_TEMPLATE_PATH = "prompts/websearch-gap-assess.md";

    /** 注入评估 Prompt 的结果条数上限（每条形如「标题：摘要」单行，控 prompt 长度）。 */
    private static final int RESULTS_IN_PROMPT_LIMIT = 12;

    /** 单条结果摘要注入评估 Prompt 的字符上限。 */
    private static final int SNIPPET_IN_PROMPT_CHARS = 120;

    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final SystemTimeContextBuilder systemTimeContextBuilder;
    private final ObjectMapper objectMapper;

    public SearchGapAssessor(PlatformAgentExecutionService platformAgentExecutionService,
                             PlannerAgentPicker plannerAgentPicker,
                             SystemTimeContextBuilder systemTimeContextBuilder,
                             ObjectMapper objectMapper) {
        this.platformAgentExecutionService = platformAgentExecutionService;
        this.plannerAgentPicker = plannerAgentPicker;
        this.systemTimeContextBuilder = systemTimeContextBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估首轮检索是否充分，不足时给出 1 个补充查询词。
     *
     * @param userMessage     用户需求原文（语义文本）
     * @param firstRound      首轮归一化结果（可为空——空结果无需评估，直接返回 null）
     * @param existingQueries 已检索过的查询词（防同义重复）
     * @return 补充查询词（1 个，已清洗）；无需补搜 / 不可用 / 失败时返回 null
     */
    public String assessGap(String userMessage, List<WebSearchResult> firstRound,
                            List<String> existingQueries) {
        if (firstRound == null || firstRound.isEmpty()) {
            return null; // 首轮无结果：无可评估素材，交由上层按原语义处理
        }
        long t0 = System.currentTimeMillis();
        try {
            String prompt = loadPrompt()
                    .replace("{{SYSTEM_TIME_CONTEXT}}", systemTimeContextBuilder.build())
                    .replace("{{USER_MESSAGE}}", userMessage == null ? "" : userMessage.trim())
                    .replace("{{EXISTING_QUERIES}}", existingQueries == null || existingQueries.isEmpty()
                            ? "（无）" : String.join("、", existingQueries))
                    .replace("{{SEARCH_RESULTS}}", renderResults(firstRound));
            Agent planner = plannerAgentPicker.pick(null); // 自动选择平台内 PLANNER
            AgentTask agentTask = AgentTask.builder()
                    .systemPrompt("")
                    .userPrompt(prompt)
                    .context(Map.of("scene", GAP_SCENE))
                    .requiredCapabilities(Map.of())
                    .build();
            AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
            if (!result.isSuccess()) {
                log.warn("检索缺口评估 LLM 失败（不补搜）: err={}", result.getErrorMessage());
                return null;
            }
            String gap = parseGapQuery(result.getOutput());
            log.info("检索缺口评估结束: costMs={}, gapQuery={}", System.currentTimeMillis() - t0, gap);
            return gap;
        } catch (Exception e) {
            log.warn("检索缺口评估失败（不补搜）: err={}", e.getMessage());
            return null;
        }
    }

    /** 评估 Prompt 的结果清单渲染：每条单行「标题：摘要（截断）」。 */
    private String renderResults(List<WebSearchResult> results) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(results.size(), RESULTS_IN_PROMPT_LIMIT);
        for (int i = 0; i < limit; i++) {
            WebSearchResult r = results.get(i);
            String snippet = r.getSnippet() == null ? "" : r.getSnippet();
            if (snippet.length() > SNIPPET_IN_PROMPT_CHARS) {
                snippet = snippet.substring(0, SNIPPET_IN_PROMPT_CHARS) + "…";
            }
            sb.append(i + 1).append(". ").append(r.getTitle() == null ? "(无标题)" : r.getTitle())
                    .append('：').append(snippet).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 宽松解析补充查询词：剥 markdown 围栏后取首个 {@code [ ... ]} 数组，
     * 取第一个有效字符串（截断到 20 字）；空数组 / 解析失败返回 null。
     */
    private String parseGapQuery(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            String s = content.replaceAll("(?i)```json", "").replace("```", "").trim();
            int from = s.indexOf('[');
            int to = s.lastIndexOf(']');
            if (from < 0 || to <= from) {
                return null;
            }
            JsonNode arr = objectMapper.readTree(s.substring(from, to + 1));
            if (!arr.isArray() || arr.isEmpty()) {
                return null;
            }
            String q = arr.get(0).asText("").trim();
            if (q.isBlank()) {
                return null;
            }
            return q.length() > 20 ? q.substring(0, 20) : q;
        } catch (Exception e) {
            return null;
        }
    }

    /** 加载评估 Prompt 模板（classpath，失败由外层 catch 降级为不补搜）。 */
    private String loadPrompt() throws Exception {
        ClassPathResource resource = new ClassPathResource(GAP_PROMPT_TEMPLATE_PATH);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
