package com.helloai.core.agent.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentQualityProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderQueryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * executorDoneIssues LLM 语义对比评估器（反馈回路第 1 层，Phase 1.4）。
 *
 * <p>入参 = 上一轮 REVIEWER 四元组 issues + 执行者本轮产出正文；经平台级 LLM
 * 判定上一轮哪些问题已被实质解决，产出严格 JSON
 * {@code {"doneIssues":["..."],"reason":"..."}}。</p>
 *
 * <p>LLM 通道走平台级凭证（{@link PlatformProviderConfigService#getApiKey}），
 * 无凭证/超时/输出不可解析一律返回 null 由调用方跳过回填——best-effort，
 * 绝不阻断执行/核验主链路，也不新增 agent 注册依赖。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutorIssueResolutionAssessor {

    private static final String PROMPT_TEMPLATE_PATH = "prompts/executor-done-issues.md";
    /** 产出正文注入限额：超长截断，控制 LLM 上下文成本。 */
    private static final int OUTPUT_LIMIT = 8000;
    /** JSON 围栏：```json ... ``` 或 ``` ... ```（大小写不敏感，允许首尾空白）。 */
    private static final Pattern JSON_FENCE =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    private final ObjectProvider<LlmProviderChatClientFactoryRegistry> chatClientFactoryRegistryProvider;
    private final PlatformProviderConfigService platformProviderConfigService;
    private final LlmProviderQueryService llmProviderQueryService;
    private final AgentQualityProperties qualityProperties;
    private final ObjectMapper objectMapper;

    /**
     * 评估上一轮 issues 在本轮产出中的解决情况。
     *
     * @param issues         上一轮四元组 issues（原文字符串列表）
     * @param executorOutput 执行者本轮产出正文
     * @return 评估结果；入参缺失 / 无平台凭证 / LLM 失败或超时 / 输出不可解析时返回 null
     */
    public IssueResolutionResult assess(List<String> issues, String executorOutput) {
        if (issues == null || issues.isEmpty() || executorOutput == null || executorOutput.isBlank()) {
            return null;
        }
        String providerCode = resolveProviderCode();
        if (providerCode == null) {
            log.debug("executorDoneIssues 语义对比：无可用平台级凭证，跳过回填");
            return null;
        }
        String apiKey = platformProviderConfigService.getApiKey(providerCode);
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            // 懒解析打破循环（CODE_STYLE §7.7）：registry 依赖链反向依赖本组件
            // （registry → MCP tool 链 → mcpToolServiceImpl → executionResultHandler
            // → executorDoneIssuesBackfiller → 本组件），运行时取容器已完成单例
            LlmProviderChatClientFactoryRegistry registry =
                    chatClientFactoryRegistryProvider.getIfAvailable();
            if (registry == null) {
                log.debug("executorDoneIssues 语义对比：chat client registry 不可用，跳过回填");
                return null;
            }
            ChatClient client = registry.createChatClient(providerCode, apiKey, null, null);
            String prompt = renderPrompt(issues, executorOutput);
            String raw = callWithTimeout(client, prompt);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return parse(raw);
        } catch (Exception e) {
            // 防御式：任何异常（含工厂路由异常）降级跳过，不阻断回写主链路
            log.warn("executorDoneIssues 语义对比失败（降级跳过回填）: provider={}, err={}",
                    providerCode, e.getMessage());
            return null;
        }
    }

    /** 平台级凭证 Provider 选择：指定配置优先，否则取第一个已启用且有凭证的 Provider。 */
    private String resolveProviderCode() {
        try {
            String configured = qualityProperties.getExecutorDoneIssuesProvider();
            if (configured != null && !configured.isBlank()) {
                String code = configured.trim();
                String key = platformProviderConfigService.getApiKey(code);
                return key != null && !key.isBlank() ? code : null;
            }
            List<LlmProvider> enabled = llmProviderQueryService.listEnabled();
            if (enabled == null) {
                return null;
            }
            for (LlmProvider provider : enabled) {
                String code = provider.getProviderCode();
                String key = platformProviderConfigService.getApiKey(code);
                if (key != null && !key.isBlank()) {
                    return code;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("executorDoneIssues 平台 Provider 选择异常（降级跳过）: err={}", e.getMessage());
            return null;
        }
    }

    /** 带独立超时的同步调用（超时降级 null；底层请求无法强杀，超时窗口外到达的结果直接丢弃）。 */
    private String callWithTimeout(ChatClient client, String prompt) {
        int timeoutSeconds = Math.max(qualityProperties.getExecutorDoneIssuesTimeoutSeconds(), 1);
        try {
            CompletableFuture<String> future =
                    CompletableFuture.supplyAsync(() -> client.prompt().user(prompt).call().content());
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("executorDoneIssues 语义对比超时（{}s，降级跳过回填）", timeoutSeconds);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("executorDoneIssues 语义对比被中断（降级跳过回填）");
            return null;
        } catch (Exception e) {
            // ExecutionException 等：底层 LLM 调用失败，降级跳过
            log.warn("executorDoneIssues 语义对比调用失败（降级跳过回填）: err={}", e.getMessage());
            return null;
        }
    }

    /** 模板渲染：issues 编号列表 + 产出正文（截断至 OUTPUT_LIMIT）。 */
    private String renderPrompt(List<String> issues, String executorOutput) throws Exception {
        String template;
        try (InputStream in = new ClassPathResource(PROMPT_TEMPLATE_PATH).getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder issueList = new StringBuilder();
        for (String issue : issues) {
            issueList.append("- ").append(issue).append('\n');
        }
        String output = executorOutput.length() > OUTPUT_LIMIT
                ? executorOutput.substring(0, OUTPUT_LIMIT) + "\n...(截断)"
                : executorOutput;
        return template
                .replace("{{issues}}", issueList.toString().trim())
                .replace("{{output}}", output);
    }

    /** strip fence 容错解析：优先取围栏内 JSON，无围栏时整体作为 JSON 尝试。 */
    private IssueResolutionResult parse(String raw) {
        String json = stripFence(raw);
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> doneIssues = new ArrayList<>();
            JsonNode doneNode = node.get("doneIssues");
            if (doneNode != null && doneNode.isArray()) {
                for (JsonNode item : doneNode) {
                    if (item != null && item.isTextual() && !item.asText().isBlank()) {
                        doneIssues.add(item.asText());
                    }
                }
            }
            String reason = node.path("reason").asText("");
            return new IssueResolutionResult(doneIssues, reason);
        } catch (Exception e) {
            log.warn("executorDoneIssues 输出 JSON 解析失败（降级跳过回填）: raw={}",
                    raw.length() > 300 ? raw.substring(0, 300) + "..." : raw);
            return null;
        }
    }

    static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = JSON_FENCE.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return raw.trim();
    }

    /**
     * 语义对比结果：doneIssues 为已解决 issue 原文列表（可为空），reason 为判定依据。
     */
    @Data
    public static class IssueResolutionResult {
        private final List<String> doneIssues;
        private final String reason;
    }
}
