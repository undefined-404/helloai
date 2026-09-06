package com.helloai.core.agent.browser.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 推送桥接实现（N-003，C3-S3）。
 *
 * <p>推送目标从 {@code agent.modelConfig.webhookUrl} 读取（外部 Browser Agent 的
 * 接收地址；agent 侧登记/注册时写入）。请求体 = Browser 执行契约（subTaskId +
 * systemPrompt + userPrompt + context），响应体 = 外部服务回传 output 文本
 * （可为 manifest JSON 产物协议）。同步请求-响应，超时走现有记录状态机。</p>
 */
@Slf4j
@Component
public class HttpBrowserAgentGateway implements BrowserAgentGateway {

    /** modelConfig 键：外部 Browser Agent 推送接收地址。 */
    public static final String CFG_WEBHOOK_URL = "webhookUrl";

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpBrowserAgentGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String push(Agent agent, AgentTask task) throws Exception {
        String webhookUrl = webhookUrl(agent);
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException(
                    "WEB_BROWSER Agent 未配置 webhookUrl（agent.modelConfig.webhookUrl）: agentId=" + agent.getId());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subTaskId", task.getSubTaskId());
        body.put("systemPrompt", task.getSystemPrompt());
        body.put("userPrompt", task.getUserPrompt());
        body.put("context", task.getContext() != null ? task.getContext() : Map.of());

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        log.info("Browser 执行推送: agentId={}, subTaskId={}, webhook={}",
                agent.getId(), task.getSubTaskId(), webhookUrl);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Browser 推送失败: agentId=" + agent.getId()
                    + ", status=" + response.statusCode() + ", body=" + truncate(response.body(), 500));
        }
        return response.body();
    }

    private String webhookUrl(Agent agent) {
        Map<String, Object> modelConfig = agent.getModelConfig();
        if (modelConfig == null) {
            return null;
        }
        Object raw = modelConfig.get(CFG_WEBHOOK_URL);
        return raw == null ? null : String.valueOf(raw);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
