package com.helloai.core.agent.mcp;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.service.AuthService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * McpAuthFilter 单测（A0-2 §6.61）：
 *
 * <ul>
 *   <li>SDK 404「Session not found」时 SESSION_AUTH 联动清理（SSE 断开即失效，不应残留 30min）</li>
 *   <li>404 body 附修复提示（重新握手 / REST 别名通道）</li>
 *   <li>非 404（如 200 正常响应）不误伤：SESSION_AUTH 保留、body 不改写</li>
 *   <li>401 无凭证仍返回 JSON-RPC 风格 error</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpAuthFilter 404 联动清理与修复提示（A0-2）")
class McpAuthFilterTest {

    private static final String SESSION_ID = "test-session-abc-123";
    private static final long AGENT_ID = 7L;

    @Mock
    private AuthService authService;

    private McpAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new McpAuthFilter(authService, new SimpleMeterRegistry());
        request = new MockHttpServletRequest("POST", "/mcp/messages");
        request.setParameter("sessionId", SESSION_ID);
        request.addHeader("Authorization", "Bearer fake-api-key");
        response = new MockHttpServletResponse();
        McpAuthContext.evict(SESSION_ID);
    }

    @AfterEach
    void tearDown() {
        McpAuthContext.evict(SESSION_ID);
    }

    private Agent mockAgent() {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setName("test-executor");
        return agent;
    }

    /** SDK 行为模拟：直接写 404 + Session not found body。 */
    private FilterChain chainWithSdk404() {
        return (req, resp) -> {
            HttpServletResponse httpResp = (HttpServletResponse) resp;
            httpResp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            httpResp.setContentType("application/json");
            httpResp.getOutputStream().write(("{\"error\":\"Session not found: " + SESSION_ID + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    @DisplayName("SDK 404 Session not found：SESSION_AUTH 联动清理 + body 附修复提示")
    void sdk404_evictsAuthAndAppendsFixHint() throws Exception {
        when(authService.validateAgentKey("fake-api-key")).thenReturn(mockAgent());
        // 模拟该 session 之前已鉴权通过
        McpAuthContext.put(SESSION_ID, AGENT_ID, "test-executor", "agent");
        assertNotNull(McpAuthContext.nameBySessionId(SESSION_ID), "前置：SESSION_AUTH 应有该 session");

        filter.doFilter(request, response, chainWithSdk404());

        assertEquals(404, response.getStatus());
        // 1) SESSION_AUTH 已联动清理（与 SDK session 生命周期对齐，不再残留 30min）
        assertNull(McpAuthContext.nameBySessionId(SESSION_ID), "404 后 SESSION_AUTH 应被联动清理");
        // 2) body 保留原始错误并追加修复提示
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Session not found"), "原始错误信息应保留");
        assertTrue(body.contains("fixHint"), "应附加 fixHint 修复提示");
        assertTrue(body.contains("/mcp/sse"), "修复提示应指向重新握手");
        assertTrue(body.contains("/api/mcp/jsonrpc"), "修复提示应指向 REST 别名通道");
    }

    @Test
    @DisplayName("正常 200 响应：SESSION_AUTH 保留、body 不改写")
    void normalResponse_keepsAuthAndBody() throws Exception {
        when(authService.validateAgentKey("fake-api-key")).thenReturn(mockAgent());
        McpAuthContext.put(SESSION_ID, AGENT_ID, "test-executor", "agent");

        FilterChain okChain = (req, resp) -> {
            HttpServletResponse httpResp = (HttpServletResponse) resp;
            httpResp.setStatus(HttpServletResponse.SC_OK);
            httpResp.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8));
        };
        filter.doFilter(request, response, okChain);

        assertEquals(200, response.getStatus());
        assertNotNull(McpAuthContext.nameBySessionId(SESSION_ID), "200 响应不应清理 SESSION_AUTH");
        assertEquals("ok", response.getContentAsString(StandardCharsets.UTF_8), "body 不应被改写");
    }

    @Test
    @DisplayName("非 Session not found 的 404（如路径 404）：不附修复提示")
    void sdk404_otherMessage_noFixHint() throws Exception {
        when(authService.validateAgentKey("fake-api-key")).thenReturn(mockAgent());
        McpAuthContext.put(SESSION_ID, AGENT_ID, "test-executor", "agent");

        FilterChain other404 = (req, resp) -> {
            HttpServletResponse httpResp = (HttpServletResponse) resp;
            httpResp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            httpResp.getOutputStream().write("{\"error\":\"No handler\"}".getBytes(StandardCharsets.UTF_8));
        };
        filter.doFilter(request, response, other404);

        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains("fixHint"),
                "非 Session not found 错误不应附修复提示");
    }

    @Test
    @DisplayName("无凭证 401：保持 JSON-RPC 风格错误响应（回归）")
    void missingCredential_401JsonRpcError() throws Exception {
        MockHttpServletRequest noAuth = new MockHttpServletRequest("POST", "/mcp/messages");
        noAuth.setParameter("sessionId", SESSION_ID);

        filter.doFilter(noAuth, response, (req, resp) -> {
            throw new AssertionError("chain 不应被调用");
        });

        assertEquals(401, response.getStatus());
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("jsonrpc"), "401 body 应为 JSON-RPC 风格");
        assertTrue(body.contains("error"), "401 body 应含 error 字段");
    }

    @Test
    @DisplayName("鉴权失败（非法 apiKey）：401 且不执行 chain")
    void badApiKey_401WithoutChain() throws Exception {
        when(authService.validateAgentKey("bad-key")).thenThrow(
                new com.helloai.common.base.BizException(401, "无效的 API Key"));
        request = new MockHttpServletRequest("POST", "/mcp/messages");
        request.setParameter("sessionId", SESSION_ID);
        request.addHeader("Authorization", "Bearer bad-key");

        filter.doFilter(request, response, (req, resp) -> {
            throw new AssertionError("chain 不应被调用");
        });

        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("sessionId 缺失时 404：evict 幂等不抛异常")
    void sdk404_withoutSessionId_noException() throws Exception {
        when(authService.validateAgentKey("fake-api-key")).thenReturn(mockAgent());
        request = new MockHttpServletRequest("POST", "/mcp/messages");
        request.addHeader("Authorization", "Bearer fake-api-key");

        filter.doFilter(request, response, chainWithSdk404());

        assertEquals(404, response.getStatus());
    }
}
