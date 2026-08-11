package com.helloai.core.agent.mcp;

import com.helloai.common.base.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MCP Server 鉴权上下文工具类（v2.4 §3.1 / §9 路线 C M4 鉴权改造 v2）。
 *
 * <p><b>核心机制</b>：用 sessionId 关联 {@link McpAuthFilter} 写入的鉴权信息与
 * spring-ai MCP Server 反射调用 @Tool 方法时的请求上下文。</p>
 *
 * <p><b>为什么不用 {@code RequestContextHolder}？</b>
 * spring-ai 1.0 GA 即使使用 SYNC 模式，{@code McpAsyncServer} 内部仍走 reactive
 * 代码，工具执行实际发生在 {@code boundedElastic} 线程池（spring-ai issue #2506）。
 * 该线程池不继承 {@code RequestContextHolder} 的 InheritableThreadLocal，
 * 导致 {@code RequestContextHolder.getRequestAttributes()} 返 null，鉴权上下文丢失。
 * 详细参考：<a href="https://stackoverflow.com/questions/79618824">StackOverflow</a></p>
 *
 * <p><b>sessionId 来源</b>：spring-ai MCP Server 在反射调用 @Tool 方法时，
 * 会把 MCP 会话 ID 放入 {@link ToolContext#getContext()} map。
 * key 因版本而异（"MCP_SESSION_ID" / "sessionId" / "session_id"），
 * 本实现按已知顺序尝试多个 key，匹配到就缓存到 SESSION_AUTH。</p>
 *
 * <p><b>存储</b>：static {@code ConcurrentMap<String, AuthContext>}，
 * 进程级缓存。sessionId 几乎不可能碰撞（UUID），依赖 SSE 长连接断开时由
 * spring-ai 内部清理 session。极端情况下可能累积（不会被 GC），
 * helloai 当前阶段可接受（v2.4 §3.1 阶段 3 收官后再优化）。</p>
 *
 * @author helloai
 * @see McpAuthFilter
 * @see McpMcpServer
 */
@Slf4j
public final class McpAuthContext {

    /**
     * sessionId → 鉴权上下文。
     * 由 {@link McpAuthFilter} 写入，{@link McpAuthContext#requireAuthIdBySessionId} 读取。
     */
    private static final ConcurrentMap<String, AuthContext> SESSION_AUTH = new ConcurrentHashMap<>();

    /**
     * 鉴权主体不可变快照。
     *
     * @param id   鉴权主体 ID（admin userId 或 agent id）
     * @param name 鉴权主体显示名
     * @param type 鉴权类型（"admin" / "agent"）
     * @param lastAccessAtMs 最后访问时间（epoch millis）
     */
    public record AuthContext(Long id, String name, String type, long lastAccessAtMs) {}

    /** request attribute key：鉴权后的主体 ID（保留兼容，McpAuthFilter 仍写 request attribute） */
    public static final String AUTH_ID = "_authId";

    /** request attribute key：鉴权主体显示名 */
    public static final String AUTH_NAME = "_authName";

    /** request attribute key：鉴权类型（"admin" / "agent"） */
    public static final String AUTH_TYPE = "_authType";

    private McpAuthContext() {
        // 工具类，禁止实例化
    }

    /**
     * 注册鉴权上下文（由 {@link McpAuthFilter} 调用）。
     *
     * @param sessionId  MCP sessionId（来自 query string ?sessionId=xxx）
     * @param id         鉴权主体 ID
     * @param name       鉴权主体显示名
     * @param type       鉴权类型（"admin" / "agent"）
     */
    public static void put(String sessionId, Long id, String name, String type) {
        if (sessionId == null || sessionId.isBlank() || id == null) {
            log.warn("McpAuthContext.put: invalid arg, sessionId={}, id={}", sessionId, id);
            return;
        }
        SESSION_AUTH.put(sessionId, new AuthContext(id, name, type, System.currentTimeMillis()));
        log.debug("McpAuthContext.put: sessionId={}, id={}, type={}", sessionId, id, type);
    }

    /**
     * 从 ToolContext 中按已知 key 顺序提取 sessionId。
     *
     * <p>key 候选顺序（spring-ai 1.0 GA → 1.1.x 兼容性）：</p>
     * <ol>
     *   <li>"MCP_SESSION_ID" — spring-ai 1.0 GA ToolCallback 内部常用</li>
     *   <li>"sessionId"      — 通用命名</li>
     *   <li>"session_id"     — snake_case 变体</li>
     * </ol>
     *
     * @param toolContext spring-ai 注入的 ToolContext（@Tool 方法参数）
     * @return 找到的 sessionId，找不到返 null
     */
    public static String extractSessionIdFromToolContext(ToolContext toolContext) {
        if (toolContext == null) return null;
        Map<String, Object> ctx = toolContext.getContext();
        if (ctx == null || ctx.isEmpty()) {
            log.warn("McpAuthContext.extractSessionIdFromToolContext: ToolContext is empty");
            return null;
        }
        String[] keys = {"MCP_SESSION_ID", "sessionId", "session_id"};
        for (String key : keys) {
            Object v = ctx.get(key);
            if (v != null) {
                log.info("McpAuthContext.extractSessionIdFromToolContext: 命中 key={}, sessionId={}", key, v);
                return v.toString();
            }
        }
        // 兜底：第一次调用时把所有 key 记到日志，辅助调试
        log.warn("McpAuthContext.extractSessionIdFromToolContext: 未找到已知 sessionId key，实际 keys={}",
                ctx.keySet());
        return null;
    }

    /**
     * 取鉴权主体 ID（从 ToolContext 提取 sessionId 后查 SESSION_AUTH），缺失时抛 401。
     *
     * <p>兼容 spring-ai 1.1.0 的隐式 sessionId 注入（如果未来版本支持）。</p>
     *
     * @param toolContext spring-ai 注入的 ToolContext
     * @return 鉴权主体 ID
     * @throws BizException 401 如果 sessionId 未鉴权或已过期
     */
    public static Long requireAuthId(ToolContext toolContext) {
        String sessionId = extractSessionIdFromToolContext(toolContext);
        return requireAuthIdBySessionId(sessionId);
    }

    /**
     * ★ 路径 1 新增：直接透传 sessionId（v2.5 M4 收官方案）。
     *
     * <p>spring-ai 1.1.0 的 {@code SyncMcpToolMethodCallback} 反射器不认识
     * {@code McpSyncServerExchange}，ToolContext.getContext() 实际为空 map，
     * sessionId 永远自动进不来。客户端必须在 arguments 里显式传 {@code _sessionId}。</p>
     *
     * @param sessionId MCP sessionId（客户端从 SSE handshake 提取后透传）
     * @return 鉴权主体 ID
     * @throws BizException 401 如果 sessionId 为空或未鉴权
     */
    public static Long requireAuthId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(401, "MCP 鉴权失败：缺少 _sessionId 参数（请从 SSE handshake 提取 sessionId 后透传）");
        }
        return requireAuthIdBySessionId(sessionId);
    }

    /**
     * 从 sessionId 查 SESSION_AUTH，缺失时抛 401。
     *
     * @param sessionId MCP sessionId
     * @return 鉴权主体 ID（admin userId 或 agent id）
     * @throws BizException 401 如果 sessionId 未鉴权或已过期
     */
    public static Long requireAuthIdBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(401, "MCP 鉴权失败：ToolContext 中无 sessionId（可能 spring-ai 版本不兼容）");
        }
        AuthContext auth = SESSION_AUTH.get(sessionId);
        if (auth == null) {
            // A0-2（§6.61）：错误带修复提示——SSE 断开后 session 即失效，需重新握手或改走 REST 别名通道
            throw new BizException(401, "MCP 鉴权失败：session 未鉴权或已过期，sessionId=" + sessionId
                    + "。修复：重新 GET /mcp/sse 握手拿新 sessionId；或改用无状态 REST 别名 POST /api/mcp/jsonrpc（无需 session）");
        }
        SESSION_AUTH.put(sessionId, new AuthContext(auth.id(), auth.name(), auth.type(), System.currentTimeMillis()));
        return auth.id();
    }

    /**
     * 取当前 session 的鉴权主体显示名（用于日志），找不到返 null。
     */
    public static String nameBySessionId(String sessionId) {
        if (sessionId == null) return null;
        AuthContext auth = SESSION_AUTH.get(sessionId);
        return auth == null ? null : auth.name();
    }

    /**
     * 取当前 session 的鉴权类型（"admin" / "agent"），找不到返 null。
     */
    public static String typeBySessionId(String sessionId) {
        if (sessionId == null) return null;
        AuthContext auth = SESSION_AUTH.get(sessionId);
        return auth == null ? null : auth.type();
    }

    /**
     * 清理指定 session（v2.6 Q3 增强：加日志）。
     *
     * <p><b>使用场景</b>：</p>
     * <ul>
     *   <li>SSE 关闭回调可靠触发时主动调用</li>
     *   <li>管理 API 强制下线单个 session</li>
     *   <li>单测 cleanup</li>
     * </ul>
     *
     * <p>无外部调用方主动触发时，{@link SessionAuthCleaner} 会按 30 min TTL 兜底清理。</p>
     */
    public static void evict(String sessionId) {
        if (sessionId == null) {
            return;
        }
        AuthContext removed = SESSION_AUTH.remove(sessionId);
        if (removed != null) {
            log.info("McpAuthContext.evict: sessionId={}, id={}, type={}", sessionId, removed.id(), removed.type());
        }
    }

    public static int evictExpired(long cutoffEpochMs) {
        int removed = 0;
        for (Map.Entry<String, AuthContext> e : SESSION_AUTH.entrySet()) {
            AuthContext v = e.getValue();
            if (v != null && v.lastAccessAtMs() < cutoffEpochMs) {
                if (SESSION_AUTH.remove(e.getKey(), v)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public static int size() {
        return SESSION_AUTH.size();
    }
}
