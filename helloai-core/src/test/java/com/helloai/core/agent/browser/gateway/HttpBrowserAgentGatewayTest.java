package com.helloai.core.agent.browser.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpBrowserAgentGateway} C3-S3 单元测试：推送契约（webhookUrl + HTTP 请求-响应）。
 *
 * <p>用 JDK 内置 {@link HttpServer} 起本地回环服务验证契约，无外部依赖。</p>
 */
@DisplayName("HttpBrowserAgentGateway 推送契约（C3-S3）")
class HttpBrowserAgentGatewayTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/browser";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private Agent agentWithWebhook(String webhook) {
        Agent a = new Agent();
        a.setId(100L);
        a.setAccessType(AgentAccessType.WEB_BROWSER);
        a.setModelConfig(webhook == null ? Map.of() : Map.of(HttpBrowserAgentGateway.CFG_WEBHOOK_URL, webhook));
        return a;
    }

    private AgentTask task() {
        return AgentTask.builder()
                .subTaskId(200L)
                .systemPrompt("sys")
                .userPrompt("抓取页面")
                .context(Map.of("goal", "抓取"))
                .build();
    }

    @Test
    @DisplayName("未配置 webhookUrl → 推送拒绝（不触 HTTP）")
    void rejectMissingWebhook() {
        HttpBrowserAgentGateway gateway = new HttpBrowserAgentGateway(mapper);
        assertThatThrownBy(() -> gateway.push(agentWithWebhook(null), task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    @DisplayName("成功：POST 请求体含 subTaskId/prompt，响应体原样返回")
    void pushOk() throws Exception {
        server.createContext("/browser", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String requestJson = new String(body, StandardCharsets.UTF_8);
            if (!requestJson.contains("\"subTaskId\":200") || !requestJson.contains("抓取页面")) {
                exchange.sendResponseHeaders(400, 0);
                exchange.close();
                return;
            }
            byte[] resp = ("{\"summary\":\"ok\",\"files\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        HttpBrowserAgentGateway gateway = new HttpBrowserAgentGateway(mapper);
        String output = gateway.push(agentWithWebhook(baseUrl), task());

        assertThat(output).isEqualTo("{\"summary\":\"ok\",\"files\":[]}");
    }

    @Test
    @DisplayName("外部服务 500 → 推送失败抛错")
    void pushServerError() {
        server.createContext("/browser", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });

        HttpBrowserAgentGateway gateway = new HttpBrowserAgentGateway(mapper);
        assertThatThrownBy(() -> gateway.push(agentWithWebhook(baseUrl), task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("推送失败");
    }
}
