package com.helloai.core.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.service.AuthService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
        // A0-2（§6.61）：包装响应缓冲 body，用于 SDK 404「Session not found」时附修复提示
        // 并联动清理 SESSION_AUTH（SDK session 绑定 SSE 连接，断开即回收，此处同步 evict 避免残留）
        BufferedResponseWrapper wrapped = new BufferedResponseWrapper(response);
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
                filterChain.doFilter(request, wrapped);
                afterMessageHandled(wrapped, sessionId);
                wrapped.flushToUnderlying();
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
                    filterChain.doFilter(request, wrapped);
                    afterMessageHandled(wrapped, sessionId);
                    wrapped.flushToUnderlying();
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
     * POST /mcp/messages 处理完成后的收尾（A0-2 §6.61）：
     *
     * <ul>
     *   <li>SDK 返回 404（session 已从 SDK sessions map 移除，即 SSE 连接已断开）时，
     *       {@link McpAuthContext#evict} 联动清理 SESSION_AUTH——SDK session 生命周期
     *       严格绑定 SSE 连接（onComplete/onTimeout 即回收），鉴权缓存不应残留到 30min TTL。</li>
     *   <li>404 body 附修复提示：SDK 仅输出 {@code Session not found: xxx}，外部 agent
     *       无法知道下一步怎么恢复，这里统一追加「重新握手 / 改走 REST 别名通道」指引。</li>
     * </ul>
     */
    private void afterMessageHandled(BufferedResponseWrapper wrapped, String sessionId) {
        if (wrapped.getStatus() != HttpStatus.NOT_FOUND.value()) {
            return;
        }
        // 1) SESSION_AUTH 联动清理（session 已失效，鉴权缓存无保留价值）
        if (sessionId != null && !sessionId.isBlank()) {
            McpAuthContext.evict(sessionId);
            log.info("MCP 404 Session not found: sessionId={}，SESSION_AUTH 联动清理", sessionId);
        }
        // 2) body 附修复提示（仅当 body 可解析为 JSON 且是 Session not found 时改写）
        String body = wrapped.getBodyAsString();
        if (body == null || body.isBlank() || !body.contains("Session not found")) {
            return;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
            map.put("fixHint", "MCP session 已失效：SSE 连接断开后 session 即被服务端回收（协议行为）。"
                    + "修复：重新 GET /mcp/sse 握手拿新 sessionId；或改用无状态 REST 别名 POST /api/mcp/jsonrpc（无需 session，同步响应）");
            String newBody = objectMapper.writeValueAsString(map);
            wrapped.resetBody(newBody);
            log.info("MCP 404 Session not found 响应已附修复提示: {}", newBody);
        } catch (Exception e) {
            log.warn("MCP 404 body 改写失败（保持原样）: {}", e.getMessage());
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

    /**
     * 缓冲响应体包装（A0-2 §6.61）。
     *
     * <p>SDK 的 {@code WebMvcSseServerTransportProvider} 用 RouterFunction 直接写
     * {@code HttpServletResponse}，404 body 不经过任何拦截器/Advice；这里把 body 缓冲到
     * 内存，{@code doFilter} 返回后再读出来改写（附修复提示）。</p>
     *
     * <p>注意：{@code setContentLength} 改为 no-op——改写后长度会变化，最终由
     * {@link #resetBody(String)} 统一重置；{@code flushBuffer} 改为 no-op，避免
     * RouterFunction 在 handler 内 flush 导致响应提前 commit 而无法改写。</p>
     */
    private static final class BufferedResponseWrapper extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        BufferedResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called on this response");
            }
            if (outputStream == null) {
                outputStream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(jakarta.servlet.WriteListener writeListener) {
                        // 同步缓冲流，无需异步监听
                    }

                    @Override
                    public void write(int b) {
                        buffer.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        buffer.write(b, off, len);
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() {
            if (outputStream != null) {
                throw new IllegalStateException("getOutputStream() has already been called on this response");
            }
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
            }
            return writer;
        }

        @Override
        public void setContentLength(int len) {
            // no-op：body 改写后长度由 resetBody 统一设置
        }

        @Override
        public void setContentLengthLong(long len) {
            // no-op
        }

        @Override
        public void flushBuffer() {
            // no-op：阻止 RouterFunction 内部 flush 提前 commit 响应
        }

        /** 读缓冲的 body 文本（UTF-8）。 */
        String getBodyAsString() {
            if (buffer.size() == 0) {
                return null;
            }
            if (writer != null) {
                writer.flush();
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        /** 重置 body 并刷新 Content-Length。 */
        void resetBody(String body) throws IOException {
            buffer.reset();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            buffer.write(bytes, 0, bytes.length);
            super.setContentLength(bytes.length);
        }

        /**
         * 把缓冲的 body 写回原始 response（A0-2 §6.61）。
         *
         * <p>filter 链结束后容器不会自动取 wrapper 缓冲的内容，必须显式把最终 body
         * 刷到底层 {@code HttpServletResponse} 输出流（含改写后的 404 body）。</p>
         */
        void flushToUnderlying() throws IOException {
            if (buffer.size() == 0) {
                return;
            }
            if (writer != null) {
                writer.flush();
            }
            HttpServletResponse underlying = (HttpServletResponse) getResponse();
            byte[] bytes = buffer.toByteArray();
            try {
                underlying.setContentLength(bytes.length);
            } catch (IllegalStateException e) {
                // 响应已 commit 时无法再改 Content-Length，忽略（容器按实际字节发送）
            }
            ServletOutputStream os = underlying.getOutputStream();
            os.write(bytes);
            os.flush();
        }
    }
}
