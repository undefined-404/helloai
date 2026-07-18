package com.helloai.core.agent.executor;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.chat.AgentChatClientService;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

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
 * <p>T3 先把平台内执行抽象和路由入口立住，真实 ChatClient 链路放到 T4/T5 接入。</p>
 */
@Slf4j
@Component
public class ApiKeyAgentExecutor implements AgentExecutor {

    private final AgentChatClientService agentChatClientService;
    private final CredentialVaultBindingService credentialVaultBindingService;
    private final AgentExecutionProperties executionProperties;

    public ApiKeyAgentExecutor(
            AgentChatClientService agentChatClientService,
            CredentialVaultBindingService credentialVaultBindingService,
            AgentExecutionProperties executionProperties) {
        this.agentChatClientService = agentChatClientService;
        this.credentialVaultBindingService = credentialVaultBindingService;
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

        String vaultApiKey;
        if (executionProperties.isMockMode()) {
            vaultApiKey = null;
        } else {
            dbg("api_key_llm_before_vault_fetch", safeMap(
                    "agentId", agent.getId(),
                    "subTaskId", task.getSubTaskId(),
                    "provider", provider
            ));
            try {
                vaultApiKey = credentialVaultBindingService.getAgentApiKeyPlaintext(agent.getId(), provider);
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                dbg("api_key_llm_vault_fetch_fail", safeMap(
                        "agentId", agent.getId(),
                        "subTaskId", task.getSubTaskId(),
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
                    "subTaskId", task.getSubTaskId(),
                    "provider", provider,
                    "hasVaultApiKey", vaultApiKey != null && !vaultApiKey.isBlank()
            ));
        }
        if (!executionProperties.isMockMode() && executionProperties.isRequireVault()
                && (vaultApiKey == null || vaultApiKey.isBlank())) {
            dbg("api_key_llm_execute_reject_missing_vault", safeMap(
                    "agentId", agent.getId(),
                    "subTaskId", task.getSubTaskId(),
                    "provider", provider
            ));
            throw new BizException("Agent 未配置启用态托管凭证: agentId=" + agent.getId() + ", provider=" + provider);
        }
        try {
            ChatResponse response = agentChatClientService.generate(
                    agent,
                    task.getSystemPrompt(),
                    task.getUserPrompt(),
                    provider,
                    vaultApiKey
            );
            String content = response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : "";
            Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            Integer totalTokens = usage != null ? usage.getTotalTokens() : null;

            log.info("API_KEY_LLM 执行完成: agentId={}, subTaskId={}, modelType={}, tokens={}",
                    agent.getId(), task.getSubTaskId(), agent.getModelType(), totalTokens);
            dbg("api_key_llm_execute_ok", safeMap(
                    "agentId", agent.getId(),
                    "subTaskId", task.getSubTaskId(),
                    "tokens", totalTokens
            ));
            return AgentResult.success(content, "STOP", getName(), totalTokens);
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

    @Override
    public boolean supports(Agent agent) {
        return agent != null && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

}
