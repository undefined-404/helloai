package com.helloai.core.agent.executor;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * API_KEY_LLM 执行器占位实现。
 *
 * <p>先把平台内执行抽象和路由入口立住，真实 ChatClient 链路放到 /接入。</p>
 */
@Slf4j
@Component
public class ApiKeyAgentExecutor implements AgentExecutor {

    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final AgentExecutionProperties executionProperties;

    public ApiKeyAgentExecutor(
            AgentChatClientService agentChatClientService,
            AgentLlmCredentialResolver agentLlmCredentialResolver,
            AgentExecutionProperties executionProperties) {
        this.agentChatClientService = agentChatClientService;
        this.agentLlmCredentialResolver = agentLlmCredentialResolver;
        this.executionProperties = executionProperties;
    }

    // #region debug-point redispatch-stuck-blocked
    private static final ObjectMapper DBG_MAPPER = new ObjectMapper();
    private static final HttpClient DBG_HTTP = HttpClient.newHttpClient();
    private static volatile String DBG_URL;

    private static String dbgUrl() {
        if (DBG_URL != null) {
            return DBG_URL;
        }
        synchronized (ApiKeyAgentExecutor.class) {
            if (DBG_URL != null) {
                return DBG_URL;
            }
            String envUrl = System.getenv("DEBUG_SERVER_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                DBG_URL = envUrl;
                return DBG_URL;
            }
            try {
                Path envFile = Path.of(".dbg", "redispatch-stuck-blocked.env");
                if (Files.exists(envFile)) {
                    for (String line : Files.readAllLines(envFile)) {
                        if (line.startsWith("DEBUG_SERVER_URL=")) {
                            String url = line.substring("DEBUG_SERVER_URL=".length()).trim();
                            if (!url.isBlank()) {
                                DBG_URL = url;
                                return DBG_URL;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
                // best-effort：调试配置读取失败即放弃，不影响主链路
            }
            return null;
        }
    }

    private static void dbg(String point, Map<String, Object> data) {
        String url = dbgUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            Map<String, Object> evt = new HashMap<>();
            evt.put("sessionId", "redispatch-stuck-blocked");
            evt.put("point", point);
            evt.put("ts", OffsetDateTime.now().toString());
            evt.put("data", data != null ? data : Map.of());
            String body = DBG_MAPPER.writeValueAsString(evt);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            DBG_HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
            // best-effort：调试上报失败忽略，不影响执行链路
        }
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }
    // #endregion debug-point redispatch-stuck-blocked

    @Override
    public AgentResult execute(Agent agent, AgentTask task) {
        final String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        dbg("api_key_llm_execute_enter", safeMap(
                "agentId", agent.getId(),
                "subTaskId", task.getSubTaskId(),
                "provider", provider,
                "mockMode", executionProperties.isMockMode(),
                "requireVault", executionProperties.isRequireVault(),
                "resolvedProviderFromConfig", executionProperties.getProvider(),
                "agentModelType", agent.getModelType()
        ));

        String vaultApiKey = resolveApiKey(agent, provider);
        try {
            ChatResponse response = agentChatClientService.generate(
                    agent,
                    task.getSystemPrompt(),
                    task.getUserPrompt(),
                    provider,
                    vaultApiKey
            );
            // 分离正文与思考过程：推理模型（如 Minimax ）的 thinking 块不混入 output
            ChatResponseContentExtractor.ExtractedContent extracted = ChatResponseContentExtractor.extract(response);
            String content = extracted.text();
            String thinking = extracted.thinking().isBlank() ? null : extracted.thinking();
            Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            Integer totalTokens = usage != null ? usage.getTotalTokens() : null;

            log.info("API_KEY_LLM 执行完成: agentId={}, subTaskId={}, modelType={}, tokens={}",
                    agent.getId(), task.getSubTaskId(), agent.getModelType(), totalTokens);
            dbg("api_key_llm_execute_ok", safeMap(
                    "agentId", agent.getId(),
                    "subTaskId", task.getSubTaskId(),
                    "tokens", totalTokens
            ));
            return AgentResult.success(content, thinking, "STOP", getName(), totalTokens);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            dbg("api_key_llm_execute_fail", safeMap(
                    "agentId", agent.getId(),
                    "subTaskId", task.getSubTaskId(),
                    "exception", e.getClass().getName(),
                    "message", e.getMessage(),
                    "rootException", root.getClass().getName(),
                    "rootMessage", root.getMessage()
            ));
            throw e;
        }
    }

    /**
     * 解析并校验 API Key（同步与流式共用）：mock 模式返回 null；真实模式优先平台级
     * （模型配置）密钥、Agent 级兜底（系统管理轮换 API Key 后实时生效），
     * 开启 requireVault 且无可用密钥时抛 BizException。
     */
    private String resolveApiKey(Agent agent, String provider) {
        if (executionProperties.isMockMode()) {
            return null;
        }
        dbg("api_key_llm_before_vault_fetch", safeMap(
                "agentId", agent.getId(),
                "provider", provider
        ));
        String vaultApiKey;
        try {
            vaultApiKey = agentLlmCredentialResolver.resolveApiKey(agent);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            dbg("api_key_llm_vault_fetch_fail", safeMap(
                    "agentId", agent.getId(),
                    "provider", provider,
                    "exception", e.getClass().getName(),
                    "message", e.getMessage(),
                    "rootException", root.getClass().getName(),
                    "rootMessage", root.getMessage()
            ));
            throw e;
        }
        dbg("api_key_llm_after_vault_fetch", safeMap(
                "agentId", agent.getId(),
                "provider", provider,
                "hasVaultApiKey", vaultApiKey != null && !vaultApiKey.isBlank()
        ));
        if (executionProperties.isRequireVault()
                && (vaultApiKey == null || vaultApiKey.isBlank())) {
            dbg("api_key_llm_execute_reject_missing_vault", safeMap(
                    "agentId", agent.getId(),
                    "provider", provider
            ));
            throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId()
                                + ", provider=" + provider + "，请先在系统管理中配置模型 API Key");
        }
        return vaultApiKey;
    }

    /**
     * 流式执行：同同步链路的凭证解析（mock/真实一致），LLM 调用走
     * {@link AgentChatClientService#generateStream} 的 token 增量通道。
     *
     * <p>凭证解析与真实 LLM 流都放进 Flux.defer：订阅时才真正发起（惰性语义），
     * 供上层在业务线程池里订阅时异步执行。</p>
     */
    @Override
    public Flux<String> executeStream(Agent agent, AgentTask task) {
        final String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        dbg("api_key_llm_stream_enter", safeMap(
                "agentId", agent.getId(),
                "subTaskId", task.getSubTaskId(),
                "provider", provider,
                "mockMode", executionProperties.isMockMode()
        ));
        return Flux.defer(() -> {
            String vaultApiKey = resolveApiKey(agent, provider);
            return agentChatClientService.generateStream(
                    agent,
                    task.getSystemPrompt(),
                    task.getUserPrompt(),
                    provider,
                    vaultApiKey
            );
        });
    }

    @Override
    public boolean supports(Agent agent) {
        return agent != null && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

}
