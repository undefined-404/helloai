package com.helloai.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.entity.Agent;
import com.helloai.core.service.AuthService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Server 鉴权 Filter（v2.4 §3.1 / §9 路线 C M4 鉴权改造）。
 *
 * <p><b>拦截范围</b>：仅 {@code POST /mcp/messages?sessionId=xxx}（spring-ai MCP Server
 * 接收 JSON-RPC 消息的端点）。{@code GET /mcp/sse} 走握手建立 SSE 长连接，<b>不</b>鉴权，
 * 客户端必须先建立连接拿到 sessionId，再 POST 带 Authorization 头。</p>
 *
 * <p><b>鉴权来源</b>（与现有 {@code AuthInterceptor} 一致）：
 * <ol>
 *   <li>{@code X-Admin-Token} 头 → {@link AuthService#validateAdminToken} → 管理员会话</li>
 *   <li>{@code Authorization: Bearer <apiKey>} 头 → {@link AuthService#validateAgentKey} → Agent 实体</li>
 *   <li>两者都缺 → 401 + JSON-RPC error 风格 body</li>
 * </ol>
 * </p>
 *
 * <p><b>鉴权后</b>：把 agentId / agentName / authType 写入
 * {@link HttpServletRequest#setAttribute request attribute}，供 spring-ai
 * {@code MethodToolCallback} 反射调用 @Tool 方法时通过 {@link McpAuthContext} 读取。</p>
 *
 * <p><b>错误响应</b>：HTTP 401 + JSON-RPC 2.0 风格 error body（便于 MCP 客户端解析）。</p>
 *
 * <p><b>注册位置</b>：见 {@link McpAuthFilterConfig}，order=HIGHEST_PRECEDENCE+10，
 * 比 {@code RequestLogInterceptor} 早执行（不写日志到 DB）。</p>
 *
 * @author helloai
 * @see McpAuthContext
 * @see McpAuthFilterConfig
 * @see com.helloai.api.interceptor.AuthInterceptor
 */
@Slf4j
public class McpAuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Counter adminSuccess;
    private final Counter agentSuccess;
    private final Counter missingCredentialFailure;
    private final Counter adminFailure;
    private final Counter agentFailure;
    private final Counter exceptionFailure;

    public McpAuthFilter(AuthService authService, MeterRegistry meterRegistry) {
        this.authService = authService;
        this.adminSuccess = meterRegistry.counter("helloai.mcp.auth", "result", "success", "type", "admin");
        this.agentSuccess = meterRegistry.counter("helloai.mcp.auth", "result", "success", "type", "agent");
        this.missingCredentialFailure = meterRegistry.counter("helloai.mcp.auth", "result", "fail", "type", "none", "reason", "missing_credential");
        this.adminFailure = meterRegistry.counter("helloai.mcp.auth", "result", "fail", "type", "admin", "reason", "biz");
        this.agentFailure = meterRegistry.counter("helloai.mcp.auth", "result", "fail", "type", "agent", "reason", "biz");
        this.exceptionFailure = meterRegistry.counter("helloai.mcp.auth", "result", "fail", "type", "unknown", "reason", "exception");
    }

    /**
     * 不需要鉴权的请求直接放行。
     * <ul>
     *   <li>非 {@code /mcp/messages} 路径（如 GET /mcp/sse 握手、静态资源）</li>
     *   <li>非 POST 方法（GET 走 SSE 握手不需要鉴权）</li>
     * </ul>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return true;
        if (!uri.startsWith("/mcp/messages")) return true;
        if (!"POST".equalsIgnoreCase(request.getMethod())) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 0) 提取 sessionId（用于 v2 鉴权上下文关联）
            String sessionId = request.getParameter("sessionId");
            if (sessionId == null) sessionId = "";

            // 1) 优先 X-Admin-Token（管理员）
            String adminToken = request.getHeader("X-Admin-Token");
            if (adminToken != null && !adminToken.isBlank()) {
                AuthService.AdminSession session = authService.validateAdminToken(adminToken);
                request.setAttribute(McpAuthContext.AUTH_ID, session.id());
                request.setAttribute(McpAuthContext.AUTH_NAME, session.displayName());
                request.setAttribute(McpAuthContext.AUTH_TYPE, "admin");
                // v2 鉴权：用 sessionId 关联（RequestContextHolder 在 boundedElastic 线程无效）
                McpAuthContext.put(sessionId, session.id(), session.displayName(), "admin");
                adminSuccess.increment();
                log.debug("MCP admin auth OK: adminId={}, name={}, sessionId={}", session.id(), session.displayName(), sessionId);
                filterChain.doFilter(request, response);
                return;
            }

            // 2) 其次 Authorization: Bearer <apiKey>（Agent）
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                String apiKey = authorization.substring(7);
                if (!apiKey.isBlank()) {
                    Agent agent = authService.validateAgentKey(apiKey);
                    request.setAttribute(McpAuthContext.AUTH_ID, agent.getId());
                    request.setAttribute(McpAuthContext.AUTH_NAME, agent.getName());
                    request.setAttribute(McpAuthContext.AUTH_TYPE, "agent");
                    // v2 鉴权：用 sessionId 关联
                    McpAuthContext.put(sessionId, agent.getId(), agent.getName(), "agent");
                    agentSuccess.increment();
                    log.debug("MCP agent auth OK: agentId={}, name={}, sessionId={}", agent.getId(), agent.getName(), sessionId);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // 3) 都没有 → 401
            missingCredentialFailure.increment();
            log.warn("MCP 鉴权失败：缺少凭证. remoteAddr={}, uri={}",
                    request.getRemoteAddr(), request.getRequestURI());
            writeUnauthorized(response, "MCP 鉴权失败：缺少 X-Admin-Token 或 Authorization Bearer <apiKey>");

        } catch (BizException e) {
            if (request.getHeader("X-Admin-Token") != null && !request.getHeader("X-Admin-Token").isBlank()) {
                adminFailure.increment();
            } else if (request.getHeader("Authorization") != null && request.getHeader("Authorization").startsWith("Bearer ")) {
                agentFailure.increment();
            } else {
                missingCredentialFailure.increment();
            }
            log.warn("MCP 鉴权业务异常: code={}, msg={}", e.getCode(), e.getMessage());
            writeUnauthorized(response, e.getMessage());
        } catch (Exception e) {
            exceptionFailure.increment();
            log.error("MCP 鉴权内部异常", e);
            writeUnauthorized(response, "MCP 鉴权失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 写 401 + JSON-RPC 2.0 风格 error body。
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", -32001);  // JSON-RPC server error 自定义范围 -32000 ~ -32099
        error.put("message", message);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("error", error);
        body.put("id", null);

        objectMapper.writeValue(response.getWriter(), body);
    }
}
