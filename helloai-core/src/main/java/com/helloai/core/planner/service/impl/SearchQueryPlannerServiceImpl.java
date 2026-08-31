package com.helloai.core.planner.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.clarify.SystemTimeContextBuilder;
import com.helloai.core.planner.service.SearchQueryPlannerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索查询规划服务实现：规则清洗（总是执行）+ LLM 改写（条件触发）。
 *
 * <p>三层结构：</p>
 * <ul>
 *   <li>规则层：去敬语/疑问前缀 → 按连接符（+/以及/，等）拆分多主题 → 去标点/语气词 →
 *       截断去重，零成本覆盖大多数场景；</li>
 *   <li>条件触发判定：规则产出 ≤1 条候选词，且原文超 30 字或含疑问句式才进 LLM，
 *       短而干净的消息（如「Python 教程」）直接搜，零额外成本；</li>
 *   <li>LLM 改写：JDK HttpClient 直连快模型 chat/completions 端点（仿
 *       {@link DeepSeekNativeSearchServiceImpl} 轻量范式），独立超时、不占 LLM 并发信号量；
 *       复用 {@code helloai.web-search.deepseek-api-key}，Key 未配置时自动禁用改写。</li>
 * </ul>
 *
 * <p>失败语义：LLM 超时/非 2xx/解析失败/空数组一律降级规则结果；
 * 规则也为空时返回空列表（调用方按空白查询词语义处理），绝不抛异常。</p>
 */
@Slf4j
@Service
public class SearchQueryPlannerServiceImpl implements SearchQueryPlannerService {

    /** 查询改写 Prompt 模板（占位符 {{USER_MESSAGE}}），与澄清模板同目录。 */
    private static final String REWRITE_PROMPT_TEMPLATE_PATH = "prompts/websearch-query-rewrite.md";

    /** 单条候选搜索词最大字数：过长查询词检索命中率低，强制截断。 */
    private static final int QUERY_CHAR_LIMIT = 20;

    /** LLM 改写条件触发的原文长度阈值（配合"规则仅单候选词"判定）。 */
    private static final int LLM_REWRITE_TEXT_LIMIT = 30;

    /** 改写输出生成 token 上限（输出仅 1~3 个搜索词的小 JSON，压低控成本）。 */
    private static final int REWRITE_MAX_TOKENS = 256;

    /**
     * 前导噪音（敬语/疑问句式开头）：反复从头部剥离直到不再命中。
     * 疑问句式与敬语对关键词检索是纯噪音，还会挤占有效词位。
     */
    private static final Pattern LEADING_NOISE_PATTERN = Pattern.compile(
            "^(能否给我提供一份|请给我提供一份|给我提供一份|能否提供一份|请提供一份|提供一份|"
                    + "能否给我|请帮我|帮我|麻烦你?|我想要一份|我想要|我需要一份|我需要|"
                    + "给我一份|给我|来一份|来个|请问|能否|能不能|可不可以|可以|"
                    + "怎么|怎样|如何|为什么|希望|想要|想)(一份|一个)?");

    /** 多主题连接符：一个查询想覆盖多个主题命中率低，拆成多个候选词分别检索。 */
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[+＋]|以及|还有|另外|，|,|、|；|;");

    /** 标点噪音：全角/半角标点、引号、括号在关键词检索里都是干扰项。 */
    private static final Pattern PUNCT_PATTERN = Pattern.compile(
            "[，。！？、；：“”‘’《》（）()【】\\[\\]{}<>·~～,.!?;:\"'`]");

    /** 疑问句式探测：条件改写判定用（规则仅产出单候选词的长句/疑问句）。 */
    private static final Pattern QUESTION_HINT_PATTERN = Pattern.compile(
            "能否|能不能|可不可以|怎么|怎样|如何|为什么|吗|？|\\?|请帮我|给我");

    /** 句尾疑问/语气词：无检索语义，尾部剥除。 */
    private static final Pattern TRAILING_PARTICLE_PATTERN = Pattern.compile("(吗|呢|吧|啊|呀)+\\s*$");

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final SystemTimeContextBuilder systemTimeContextBuilder;
    private final HttpClient httpClient;

    public SearchQueryPlannerServiceImpl(WebSearchProperties properties, ObjectMapper objectMapper,
                                         SystemTimeContextBuilder systemTimeContextBuilder) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.systemTimeContextBuilder = systemTimeContextBuilder;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getQueryRewriteTimeoutMs()))
                .build();
    }

    @Override
    public List<String> planQueries(String semanticText) {
        String text = semanticText == null ? "" : semanticText.trim();
        List<String> ruleQueries = rulePlan(text);
        if (!shouldLlmRewrite(text, ruleQueries)) {
            return ruleQueries;
        }
        List<String> rewritten = llmRewrite(text);
        if (rewritten.isEmpty()) {
            log.info("搜索词 LLM 改写未产出可用结果，降级规则结果: rules={}", ruleQueries);
            return ruleQueries;
        }
        log.info("搜索词 LLM 改写成功: rewritten={}, ruleFallback={}", rewritten, ruleQueries);
        return rewritten;
    }

    /** 规则层：前导噪音剥离 → 连接符拆分 → 标点/语气词清洗 → 截断去重（总是执行）。 */
    private List<String> rulePlan(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        String stripped = stripLeadingNoise(text);
        int max = Math.max(1, properties.getMaxQueries());
        List<String> out = new ArrayList<>();
        for (String part : SPLIT_PATTERN.split(stripped)) {
            String clean = PUNCT_PATTERN.matcher(part).replaceAll(" ");
            clean = TRAILING_PARTICLE_PATTERN.matcher(clean).replaceAll("");
            clean = clean.replaceAll("\\s+", " ").trim();
            if (clean.isBlank()) {
                continue;
            }
            if (clean.length() > QUERY_CHAR_LIMIT) {
                clean = clean.substring(0, QUERY_CHAR_LIMIT);
            }
            if (!out.contains(clean)) {
                out.add(clean);
            }
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    /** 反复剥离开头敬语/疑问句式，直到不再命中；避免剥空（剥空时保留上一轮文本）。 */
    private String stripLeadingNoise(String text) {
        String s = text.trim();
        while (true) {
            Matcher m = LEADING_NOISE_PATTERN.matcher(s);
            if (!m.find() || m.end() == 0) {
                break;
            }
            String next = s.substring(m.end()).trim();
            if (next.isBlank()) {
                break;
            }
            s = next;
        }
        return s;
    }

    /**
     * LLM 改写条件触发判定：开关开 + Key 已配置 + 规则仅产出单候选词，
     * 且（原文超阈值长度 或 含疑问句式）才改写；短而干净的消息零额外成本。
     */
    private boolean shouldLlmRewrite(String text, List<String> ruleQueries) {
        if (!properties.isQueryRewriteEnabled()) {
            return false;
        }
        String key = properties.getDeepseekApiKey();
        if (key == null || key.isBlank()) {
            return false;
        }
        if (ruleQueries.size() > 1) {
            return false;
        }
        return text.length() > LLM_REWRITE_TEXT_LIMIT || QUESTION_HINT_PATTERN.matcher(text).find();
    }

    /**
     * LLM 改写：HttpClient 直连快模型 chat/completions，独立超时；
     * 任何异常/非 2xx/解析失败返回空列表，由 {@link #planQueries} 降级规则结果。
     */
    private List<String> llmRewrite(String text) {
        long t0 = System.currentTimeMillis();
        try {
            String prompt = loadRewritePrompt()
                    .replace("{{USER_MESSAGE}}", text)
                    // 每轮改写前注入系统当前时间（第一层防线）：
                    // 改写器把"今天/上周五"等相对时间词转绝对日期时以服务器实时时钟为准
                    .replace("{{SYSTEM_TIME_CONTEXT}}", systemTimeContextBuilder.build());
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", properties.getQueryRewriteModel(),
                    "max_tokens", REWRITE_MAX_TOKENS,
                    "temperature", 0,
                    "messages", List.of(Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getQueryRewriteBaseUrl()))
                    .timeout(Duration.ofMillis(properties.getQueryRewriteTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getDeepseekApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("搜索词改写返回非 2xx（降级规则结果）: status={}, body={}",
                        response.statusCode(), truncate(response.body(), 200));
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            List<String> parsed = parseQueries(content);
            log.info("搜索词 LLM 改写调用结束: costMs={}, parsed={}", System.currentTimeMillis() - t0, parsed);
            return parsed;
        } catch (Exception e) {
            log.warn("搜索词 LLM 改写失败（降级规则结果）: err={}", e.getMessage());
            return List.of();
        }
    }

    /** 加载改写 Prompt 模板（classpath，失败由外层 catch 降级）。 */
    private String loadRewritePrompt() throws IOException {
        ClassPathResource resource = new ClassPathResource(REWRITE_PROMPT_TEMPLATE_PATH);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 改写输出宽松解析：剥围栏后取首个 [ ... ] 区间按字符串数组解析，
     * 兼容 {"queries":[...]} 对象包裹与裸数组两种形态；逐条清洗截断去重。
     */
    private List<String> parseQueries(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            String s = content.replaceAll("(?i)```json", "").replace("```", "").trim();
            int from = s.indexOf('[');
            int to = s.lastIndexOf(']');
            if (from < 0 || to <= from) {
                return out;
            }
            JsonNode arr = objectMapper.readTree(s.substring(from, to + 1));
            if (!arr.isArray()) {
                return out;
            }
            int max = Math.max(1, properties.getMaxQueries());
            for (JsonNode n : arr) {
                String q = n.isTextual() ? n.asText().trim() : "";
                if (q.isBlank()) {
                    continue;
                }
                if (q.length() > QUERY_CHAR_LIMIT) {
                    q = q.substring(0, QUERY_CHAR_LIMIT);
                }
                if (!out.contains(q)) {
                    out.add(q);
                }
                if (out.size() >= max) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("搜索词改写输出解析失败（降级规则结果）: err={}", e.getMessage());
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
