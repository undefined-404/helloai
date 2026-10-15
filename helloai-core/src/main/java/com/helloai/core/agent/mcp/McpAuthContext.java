package com.helloai.core.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP Server 鉴权上下文（§3.1 / §9 鉴权接入；D-2 分布式化，2026-10-15）。
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
 * 本实现按已知顺序尝试多个 key；1.1.x 下 ToolContext 实为空 map，
 * 由客户端在 arguments 显式透传 {@code _sessionId}（见
 * {@link #requireAuthId(String)}）。</p>
 *
 * <p><b>存储（D-2，2026-10-15 由进程级 static map 外置 Redis）</b>：
 * 原实现为 {@code static ConcurrentMap} 进程级注册表 = 单实例假设——
 * 多实例部署下鉴权写入（握手/POST 落 A）与工具调用（落 B）不保证同实例，
 * B 上查不到即返 401「session 未鉴权」，现象像鉴权 bug 而非架构 bug。
 * 现改为 <b>Redis 单一事实源</b>（{@link RBucket} + JSON + TTL，
 * key 见 {@link #KEY_PREFIX}，格式遵 CODE_STYLE §20.1），并消灭
 * {@code SessionAuthCleaner} / {@code McpSessionAuthCleanupTask} 两个
 * 进程内 TTL 清理器（Redis TTL 分布式且免扫描，原两者阈值 30/120min
 * 互相打架的问题一并消除）。TTL 为<b>空闲窗口</b>：每次读取滑动续期，
 * 活跃会话不会过期（与原 lastAccessAtMs 刷新语义等价）。</p>
 *
 * <p><b>失败模式（fail-closed，可自愈性排序）</b>：
 * <ul>
 *   <li>写失败（{@link #put}）⇒ 抛异常，由 {@link McpAuthFilter} 统一转 401
 *       「服务内部错误，请稍后重试」——宁可当场可见可重试，也不要延迟成
 *       工具调用时误导性的「session 未鉴权」；</li>
 *   <li>读失败 ⇒ 401「鉴权服务暂时不可用，请稍后重试」（不回显内部细节）；</li>
 *   <li>续期失败 ⇒ 仅 WARN，不推翻已成功的读取结论（最坏到期后重握手）；</li>
 *   <li>清理失败 ⇒ 仅 WARN，不阻断 404 修复提示响应。</li>
 * </ul>
 * Redis 已是本平台关键设施（Sa-Token 会话 / 分布式锁），本项不新增依赖面。</p>
 *
 * @author helloai
 * @see McpAuthFilter
 * @see McpMcpServer
 */
@Slf4j
@Component
public class McpAuthContext {

    /**
     * Redis key 前缀（CODE_STYLE §20.1：{@code helloai:{domain}:{resource}:{id}}）。
     * 完整 key = 前缀 + sessionId。
     */
    static final String KEY_PREFIX = "helloai:mcp:session-auth:";

    /**
     * 鉴权主体不可变快照。
     *
     * @param id   鉴权主体 ID（admin userId 或 agent id）
     * @param name 鉴权主体显示名
     * @param type 鉴权类型（"admin" / "agent"）
     */
    public record AuthContext(Long id, String name, String type) {}

    /** request attribute key：鉴权后的主体 ID（保留兼容，McpAuthFilter 仍写 request attribute） */
    public static final String AUTH_ID = "_authId";

    /** request attribute key：鉴权主体显示名 */
    public static final String AUTH_NAME = "_authName";

    /** request attribute key：鉴权类型（"admin" / "agent"） */
    public static final String AUTH_TYPE = "_authType";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    /**
     * 会话空闲 TTL（分钟）。每次读取滑动续期 ⇒ 活跃会话不过期；
     * 仅空闲超过该窗口的会话失效（与原 30min 清理阈值同口径，
     * 取代 core {@code SessionAuthCleaner}（30min，文档化主方案）与
     * job {@code McpSessionAuthCleanupTask}（120min）互相打架的旧态）。
     */
    private final long ttlMinutes;

    public McpAuthContext(RedissonClient redissonClient,
                          ObjectMapper objectMapper,
                          @Value("${helloai.mcp.session-auth-ttl-minutes:30}") long ttlMinutes) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * 注册鉴权上下文（由 {@link McpAuthFilter} 每次认证 POST 时调用）。
     *
     * <p>同一 sessionId 重复 PUT 即覆写（与客户端重连/重新鉴权语义一致）。</p>
     *
     * @param sessionId MCP sessionId（来自 query string ?sessionId=xxx）
     * @param id        鉴权主体 ID
     * @param name      鉴权主体显示名
     * @param type      鉴权类型（"admin" / "agent"）
     * @throws RuntimeException Redis 写入失败时上抛（由 filter 统一转 401 可重试错误），
     *                          绝不静默「假装成功」——那会把故障延迟成工具调用的假 401
     */
    public void put(String sessionId, Long id, String name, String type) {
        if (sessionId == null || sessionId.isBlank() || id == null) {
            log.warn("McpAuthContext.put: invalid arg, sessionId={}, id={}", sessionId, id);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(new AuthContext(id, name, type));
            bucket(sessionId).set(json, ttlMinutes, TimeUnit.MINUTES);
            log.debug("McpAuthContext.put: sessionId={}, id={}, type={}, ttlMinutes={}",
                    sessionId, id, type, ttlMinutes);
        } catch (Exception e) {
            // 写失败 ⇒ 后续工具调用必然 401；在此刻 fail-fast，让客户端拿到
            // 「服务内部错误，请稍后重试」而非延迟的「session 未鉴权」假消息。
            // 包装而非原样上抛：JsonProcessingException 是受检异常，会污染调用方签名；
            // filter 的兜底 catch 只回稳定文案，包装消息不会外泄。
            log.error("McpAuthContext.put: redis write failed, sessionId={}, id={}", sessionId, id, e);
            throw new RuntimeException("McpAuthContext.put failed", e);
        }
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
    public String extractSessionIdFromToolContext(ToolContext toolContext) {
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
     * 取鉴权主体 ID（从 ToolContext 提取 sessionId 后查 Redis），缺失时抛 401。
     *
     * <p>兼容 spring-ai 1.1.0 的隐式 sessionId 注入（如果未来版本支持）。</p>
     *
     * @param toolContext spring-ai 注入的 ToolContext
     * @return 鉴权主体 ID
     * @throws BizException 401 如果 sessionId 未鉴权或已过期
     */
    public Long requireAuthId(ToolContext toolContext) {
        String sessionId = extractSessionIdFromToolContext(toolContext);
        return requireAuthIdBySessionId(sessionId);
    }

    /**
     * 直接透传 sessionId（鉴权收官方案）。
     *
     * <p>spring-ai 1.1.0 的 {@code SyncMcpToolMethodCallback} 反射器不认识
     * {@code McpSyncServerExchange}，ToolContext.getContext() 实际为空 map，
     * sessionId 永远自动进不来。客户端必须在 arguments 里显式传 {@code _sessionId}。</p>
     *
     * @param sessionId MCP sessionId（客户端从 SSE handshake 提取后透传）
     * @return 鉴权主体 ID
     * @throws BizException 401 如果 sessionId 为空或未鉴权
     */
    public Long requireAuthId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(401, "MCP 鉴权失败：缺少 _sessionId 参数（请从 SSE handshake 提取 sessionId 后透传）");
        }
        return requireAuthIdBySessionId(sessionId);
    }

    /**
     * 从 sessionId 查 Redis，缺失时抛 401；命中则滑动续期 TTL。
     *
     * @param sessionId MCP sessionId
     * @return 鉴权主体 ID（admin userId 或 agent id）
     * @throws BizException 401 如果 sessionId 未鉴权或已过期
     */
    public Long requireAuthIdBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException(401, "MCP 鉴权失败：ToolContext 中无 sessionId（可能 spring-ai 版本不兼容）");
        }
        AuthContext auth = read(sessionId);
        refreshTtl(sessionId);
        return auth.id();
    }

    /**
     * 取当前 session 的鉴权主体显示名（用于日志 / 测试断言），找不到返 null。
     *
     * <p>诊断用途：不续期 TTL（读取不应延长会话寿命）。</p>
     */
    public String nameBySessionId(String sessionId) {
        if (sessionId == null) return null;
        AuthContext auth = readQuietly(sessionId);
        return auth == null ? null : auth.name();
    }

    /**
     * 取当前 session 的鉴权类型（"admin" / "agent"），找不到返 null。
     *
     * <p>诊断用途：不续期 TTL。</p>
     */
    public String typeBySessionId(String sessionId) {
        if (sessionId == null) return null;
        AuthContext auth = readQuietly(sessionId);
        return auth == null ? null : auth.type();
    }

    /**
     * 清理指定 session。
     *
     * <p><b>使用场景</b>：</p>
     * <ul>
     *   <li>{@link McpAuthFilter} 收到 SDK 404「Session not found」时联动清理
     *       （SSE 断开即失效，不应等 TTL 自然过期）</li>
     *   <li>管理 API 强制下线单个 session（预留）</li>
     *   <li>单测 cleanup</li>
     * </ul>
     *
     * <p>best-effort：清理失败仅 WARN，不阻断调用方主流程（如 404 修复提示改写）。</p>
     */
    public void evict(String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            boolean deleted = bucket(sessionId).delete();
            if (deleted) {
                log.info("McpAuthContext.evict: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("McpAuthContext.evict: redis delete failed, sessionId={}", sessionId, e);
        }
    }

    // ================================================================
    // 内部
    // ================================================================

    private RBucket<String> bucket(String sessionId) {
        return redissonClient.getBucket(KEY_PREFIX + sessionId);
    }

    /**
     * 读 + 解析；不存在 / 损坏 / Redis 故障一律映射为 401 BizException
     * （fail-closed：鉴权面不放行；消息区分「未鉴权」与「服务不可用」）。
     */
    private AuthContext read(String sessionId) {
        String json;
        try {
            json = bucket(sessionId).get();
        } catch (Exception e) {
            log.error("McpAuthContext.read: redis read failed, sessionId={}", sessionId, e);
            throw new BizException(401, "MCP 鉴权失败：鉴权服务暂时不可用，请稍后重试");
        }
        if (json == null || json.isBlank()) {
            // 错误带修复提示——SSE 断开后 session 即失效，需重新握手或改走 REST 别名通道
            throw new BizException(401, "MCP 鉴权失败：session 未鉴权或已过期，sessionId=" + sessionId
                    + "。修复：重新 GET /mcp/sse 握手拿新 sessionId；或改用无状态 REST 别名 POST /api/mcp/jsonrpc（无需 session）");
        }
        try {
            return objectMapper.readValue(json, AuthContext.class);
        } catch (Exception e) {
            log.error("McpAuthContext.read: payload corrupted, sessionId={}, json={}", sessionId, json, e);
            throw new BizException(401, "MCP 鉴权失败：鉴权上下文损坏，请重新 GET /mcp/sse 握手");
        }
    }

    /** 诊断读：任何失败都返 null（调用方按「无会话」处理），不抛。 */
    private AuthContext readQuietly(String sessionId) {
        try {
            String json = bucket(sessionId).get();
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, AuthContext.class);
        } catch (Exception e) {
            log.warn("McpAuthContext.readQuietly: failed, sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 滑动续期。读已成功 ⇒ 续期失败不推翻本次鉴权结论（最坏到期后需重握手）。
     */
    private void refreshTtl(String sessionId) {
        try {
            bucket(sessionId).expire(ttlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("McpAuthContext.refreshTtl: redis expire failed, sessionId={}, ttlMinutes={}",
                    sessionId, ttlMinutes, e);
        }
    }
}
