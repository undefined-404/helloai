package com.helloai.core.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpAuthContext} 单测（D-2：Redis 外置后的行为契约）。
 *
 * <p>用「按 key 分发的内存假 bucket」承接 RedissonClient，覆盖：</p>
 * <ul>
 *   <li>put / get 往返（JSON 序列化保持 id/name/type）</li>
 *   <li>TTL：写入带 TTL、读取滑动续期、配置值生效</li>
 *   <li>401 分支：session 缺失 / 空白 / 载荷损坏 / _sessionId 缺失</li>
 *   <li>失败模式：读失败 → 401「暂时不可用」（不回显内部细节）；写失败 → 上抛（fail-fast）；
 *       evict / 续期失败 → best-effort 不抛</li>
 *   <li>诊断读（name/type）：不存在返 null、不抛</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("McpAuthContext（D-2 Redis 外置）")
class McpAuthContextTest {

    private static final String SID = "sess-1";
    private static final String SID2 = "sess-2";

    @Mock
    private RedissonClient redissonClient;

    /** 内存假 Redis：完整 key → JSON 值。 */
    private final Map<String, String> store = new HashMap<>();
    /** 记录每个 key 最近一次 expire 的 TTL 分钟数（验证滑动续期）。 */
    private final Map<String, Long> lastExpireMinutes = new HashMap<>();

    private McpAuthContext ctx;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.<String>getBucket(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            RBucket<String> b = mock(RBucket.class);
            lenient().when(b.get()).thenAnswer(i -> store.get(key));
            lenient().doAnswer(i -> {
                store.put(key, i.getArgument(0));
                return true;
            }).when(b).set(anyString(), anyLong(), any(TimeUnit.class));
            lenient().doAnswer(i -> {
                lastExpireMinutes.put(key, i.getArgument(0));
                return true;
            }).when(b).expire(anyLong(), any(TimeUnit.class));
            lenient().when(b.delete()).thenAnswer(i -> store.remove(key) != null);
            return b;
        });
        ctx = new McpAuthContext(redissonClient, new ObjectMapper(), 30L);
    }

    private String keyOf(String sessionId) {
        return McpAuthContext.KEY_PREFIX + sessionId;
    }

    @Nested
    @DisplayName("put / get 往返")
    class PutGet {

        @Test
        @DisplayName("put 后 requireAuthId 取回同一主体，name/type 经 JSON 往返保持")
        void putThenRequire_roundTrips() {
            ctx.put(SID, 42L, "alice", "admin");

            assertEquals(42L, ctx.requireAuthIdBySessionId(SID));
            assertEquals("alice", ctx.nameBySessionId(SID));
            assertEquals("admin", ctx.typeBySessionId(SID));
        }

        @Test
        @DisplayName("put 写 JSON + 带 TTL（key 格式遵 CODE_STYLE §20.1）")
        void put_writesJsonWithTtl() {
            ctx.put(SID, 42L, "alice", "admin");

            String json = store.get(keyOf(SID));
            assertTrue(json.contains("\"id\":42"), "JSON 应含 id");
            assertTrue(json.contains("\"name\":\"alice\""), "JSON 应含 name");
            assertTrue(json.contains("\"type\":\"admin\""), "JSON 应含 type");
        }

        @Test
        @DisplayName("非法参数（blank sessionId / null id）：不触 Redis、不抛")
        void put_invalidArgs_noRedisNoThrow() {
            assertDoesNotThrow(() -> ctx.put(null, 1L, "n", "agent"));
            assertDoesNotThrow(() -> ctx.put("  ", 1L, "n", "agent"));
            assertDoesNotThrow(() -> ctx.put(SID, null, "n", "agent"));
            assertTrue(store.isEmpty(), "非法参数不应写入 Redis");
        }

        @Test
        @DisplayName("同一 sessionId 重复 put = 覆写（重连/重新鉴权语义）")
        void put_sameSession_overwrites() {
            ctx.put(SID, 1L, "a", "agent");
            ctx.put(SID, 2L, "b", "admin");

            assertEquals(2L, ctx.requireAuthIdBySessionId(SID));
            assertEquals("b", ctx.nameBySessionId(SID));
        }
    }

    @Nested
    @DisplayName("TTL 滑动续期")
    class Ttl {

        @Test
        @DisplayName("读取命中后续期 TTL（活跃会话不过期——原 lastAccessAtMs 刷新语义的等价物）")
        void require_refreshesTtl() {
            ctx.put(SID, 42L, "alice", "admin");
            lastExpireMinutes.clear();

            ctx.requireAuthIdBySessionId(SID);

            assertEquals(30L, lastExpireMinutes.get(keyOf(SID)), "读取后续期到配置 TTL");
        }

        @Test
        @DisplayName("配置 TTL 值生效（非硬编码 30）")
        void customTtl_honored() {
            McpAuthContext shortCtx = new McpAuthContext(redissonClient, new ObjectMapper(), 5L);
            shortCtx.put(SID, 42L, "alice", "admin");

            shortCtx.requireAuthIdBySessionId(SID);
            assertEquals(5L, lastExpireMinutes.get(keyOf(SID)));
        }

        @Test
        @DisplayName("诊断读（name/type）不续期：读取不应延长会话寿命")
        void diagnosticRead_doesNotRefresh() {
            ctx.put(SID, 42L, "alice", "admin");
            lastExpireMinutes.clear();

            ctx.nameBySessionId(SID);
            ctx.typeBySessionId(SID);

            assertFalse(lastExpireMinutes.containsKey(keyOf(SID)), "诊断读不应触发 expire");
        }
    }

    @Nested
    @DisplayName("401 分支（fail-closed）")
    class Unauthorized {

        @Test
        @DisplayName("session 不存在 → 401 + 修复提示（含重握手 / REST 别名指引）")
        void missingSession_401WithFixHint() {
            BizException e = assertThrows(BizException.class, () -> ctx.requireAuthIdBySessionId("nope"));
            assertEquals(401, e.getCode());
            assertTrue(e.getMessage().contains("未鉴权或已过期"), e.getMessage());
            assertTrue(e.getMessage().contains("/mcp/sse"), e.getMessage());
            assertTrue(e.getMessage().contains("/api/mcp/jsonrpc"), e.getMessage());
        }

        @Test
        @DisplayName("sessionId 空白 → 401（spring-ai 版本不兼容提示）")
        void blankSessionId_401() {
            BizException e = assertThrows(BizException.class, () -> ctx.requireAuthIdBySessionId("  "));
            assertEquals(401, e.getCode());
            assertTrue(e.getMessage().contains("无 sessionId"), e.getMessage());
        }

        @Test
        @DisplayName("requireAuthId(String) 空白 → 401 提示补传 _sessionId")
        void requireAuthIdString_blank_401() {
            BizException e = assertThrows(BizException.class, () -> ctx.requireAuthId((String) null));
            assertEquals(401, e.getCode());
            assertTrue(e.getMessage().contains("_sessionId"), e.getMessage());
        }

        @Test
        @DisplayName("Redis 载荷损坏（非 JSON）→ 401 提示重新握手，不抛解析细节")
        void corruptedPayload_401() {
            store.put(keyOf(SID), "not-a-json");

            BizException e = assertThrows(BizException.class, () -> ctx.requireAuthIdBySessionId(SID));
            assertEquals(401, e.getCode());
            assertTrue(e.getMessage().contains("损坏"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("失败模式")
    class FailureModes {

        @Test
        @DisplayName("读失败（Redis 故障）→ 401「暂时不可用」，不回显内部异常")
        void readFailure_401NoInternalLeak() {
            RBucket<String> broken = mock(RBucket.class);
            when(broken.get()).thenThrow(new RuntimeException("redis connection reset"));
            when(redissonClient.<String>getBucket(keyOf(SID))).thenReturn(broken);
            BizException e = assertThrows(BizException.class, () -> ctx.requireAuthIdBySessionId(SID));
            assertEquals(401, e.getCode());
            assertTrue(e.getMessage().contains("暂时不可用"), e.getMessage());
            assertFalse(e.getMessage().contains("redis"), "不得回显内部细节: " + e.getMessage());
        }

        @Test
        @DisplayName("写失败（Redis 故障）→ 上抛（fail-fast，由 filter 转可重试 401）")
        void putFailure_propagates() {
            RBucket<String> broken = mock(RBucket.class);
            doThrow(new RuntimeException("redis down")).when(broken)
                    .set(anyString(), anyLong(), any(TimeUnit.class));
            when(redissonClient.<String>getBucket(keyOf(SID))).thenReturn(broken);

            assertThrows(RuntimeException.class, () -> ctx.put(SID, 1L, "n", "agent"));
        }

        @Test
        @DisplayName("续期失败不推翻已成功的读取（最坏到期后重握手）")
        void refreshFailure_doesNotBreakRead() {
            ctx.put(SID, 42L, "alice", "admin");
            RBucket<String> flaky = mock(RBucket.class);
            lenient().when(flaky.get()).thenAnswer(i -> store.get(keyOf(SID)));
            doThrow(new RuntimeException("redis down")).when(flaky).expire(anyLong(), any(TimeUnit.class));
            when(redissonClient.<String>getBucket(keyOf(SID))).thenReturn(flaky);

            assertEquals(42L, ctx.requireAuthIdBySessionId(SID), "读已成功 ⇒ 续期失败不影响结论");
        }

        @Test
        @DisplayName("evict 失败 best-effort：不抛（不阻断 404 修复提示改写）")
        void evictFailure_swallowed() {
            RBucket<String> broken = mock(RBucket.class);
            doThrow(new RuntimeException("redis down")).when(broken).delete();
            when(redissonClient.<String>getBucket(keyOf(SID))).thenReturn(broken);

            assertDoesNotThrow(() -> ctx.evict(SID));
        }

        @Test
        @DisplayName("evict null 安全（幂等）")
        void evictNull_safe() {
            assertDoesNotThrow(() -> ctx.evict(null));
        }
    }

    @Nested
    @DisplayName("evict 语义")
    class Evict {

        @Test
        @DisplayName("evict 后读取 401；再次 evict 幂等")
        void evict_thenRequire401() {
            ctx.put(SID, 42L, "alice", "admin");
            ctx.evict(SID);

            assertNull(ctx.nameBySessionId(SID));
            assertThrows(BizException.class, () -> ctx.requireAuthIdBySessionId(SID));
            assertDoesNotThrow(() -> ctx.evict(SID), "重复 evict 幂等");
        }

        @Test
        @DisplayName("evict 只删目标 session（多会话互不干扰）")
        void evict_scopedToSession() {
            ctx.put(SID, 1L, "a", "agent");
            ctx.put(SID2, 2L, "b", "agent");

            ctx.evict(SID);

            assertNull(ctx.nameBySessionId(SID));
            assertEquals(2L, ctx.requireAuthIdBySessionId(SID2));
        }
    }

    @Test
    @DisplayName("诊断读在 Redis 故障时返 null 不抛（调用方按无会话处理）")
    void diagnosticRead_failure_returnsNull() {
        RBucket<String> broken = mock(RBucket.class);
        when(broken.get()).thenThrow(new RuntimeException("redis down"));
        when(redissonClient.<String>getBucket(anyString())).thenReturn(broken);

        assertNull(ctx.nameBySessionId(SID));
        assertNull(ctx.typeBySessionId(SID));
    }
}
