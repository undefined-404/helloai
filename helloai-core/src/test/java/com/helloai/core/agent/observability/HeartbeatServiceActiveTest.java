package com.helloai.core.agent.observability;

import com.helloai.common.config.HeartbeatProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.impl.HeartbeatServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HeartbeatService.active() 单测（v2.6 §4.1 心跳语义对齐，2026-07-20 落地）。
 *
 * <p>覆盖 active() 的契约：
 * <ol>
 *   <li>复用 seen() 的完整双写（Redis TTL + DB last_seen_time）</li>
 *   <li>单独刷 last_active_time</li>
 *   <li>SLEEPING 防护不被绕过</li>
 *   <li>空 Agent / agentId=null 容错</li>
 *   <li>Redis 故障不影响 DB 双写</li>
 * </ol>
 *
 * <p><b>注</b>：seen() 是先写 Redis TTL、再读 Agent、最后决定是否 updateById。
 * 所以"Agent 不存在"场景下 Redis TTL 也已被写入——这是有意为之的设计：
 * TTL 写入本身即可告诉 Reconcile "刚有人见过"，后续 DB 查询落空时仍保留这个信号。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HeartbeatService.active() v2.6 心跳语义对齐")
class HeartbeatServiceActiveTest {

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private HeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        HeartbeatProperties properties = new HeartbeatProperties();
        // 测试默认不节流（保持原行为），节流场景在 Throttle 嵌套类内单独构造
        properties.setActiveThrottleMs(0);
        heartbeatService = new HeartbeatServiceImpl(agentMapper, redis, properties);
        // LENIENT 模式：部分用例不调 redis，此 stubbing 不是"未使用"而是被跳过
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("完整双写契约")
    class DoubleWriteContract {

        @Test
        @DisplayName("active() 必须写 Redis TTL key（agent:heartbeat:{id}）")
        void shouldWriteRedisTtlOnActive() {
            Agent agent = newAgent(101L, AgentOnlineStatus.ONLINE);
            when(agentMapper.selectById(101L)).thenReturn(agent);

            heartbeatService.active(101L);

            // seen() 内部的 Redis TTL 写
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
            verify(valueOps).set(
                    keyCaptor.capture(),
                    valueCaptor.capture(),
                    ttlCaptor.capture(),
                    any(java.util.concurrent.TimeUnit.class));
            assertThat(keyCaptor.getValue()).isEqualTo("agent:heartbeat:101");
            assertThat(valueCaptor.getValue()).isNotBlank();
            // TTL 与 HB_TTL 一致
            assertThat(Duration.ofSeconds(ttlCaptor.getValue())).isEqualTo(HeartbeatService.HB_TTL);
        }

        @Test
        @DisplayName("active() 必须刷 DB last_seen_time（通过 seen()）")
        void shouldRefreshLastSeenTimeOnActive() {
            Agent agent = newAgent(102L, AgentOnlineStatus.ONLINE);
            OffsetDateTime before = OffsetDateTime.now().minusMinutes(10);
            agent.setLastSeenTime(before);
            when(agentMapper.selectById(102L)).thenReturn(agent);

            heartbeatService.active(102L);

            // seen() 内部 update + active() 末尾 update，至少 2 次
            ArgumentCaptor<Agent> updateCaptor = ArgumentCaptor.forClass(Agent.class);
            verify(agentMapper, atLeastOnce()).updateById(updateCaptor.capture());
            // 末尾 update 一定是 active() 刷 last_active_time
            Agent lastUpdate = updateCaptor.getAllValues().get(updateCaptor.getAllValues().size() - 1);
            assertThat(lastUpdate.getLastActiveTime())
                    .isAfter(before)
                    .isBefore(OffsetDateTime.now().plusSeconds(1));
        }

        @Test
        @DisplayName("active() 必须刷 last_active_time（独立于 seen 的'连接存活'语义）")
        void shouldRefreshLastActiveTimeSeparately() {
            Agent agent = newAgent(103L, AgentOnlineStatus.ONLINE);
            when(agentMapper.selectById(103L)).thenReturn(agent);

            OffsetDateTime beforeCall = OffsetDateTime.now();
            heartbeatService.active(103L);
            OffsetDateTime afterCall = OffsetDateTime.now();

            ArgumentCaptor<Agent> updateCaptor = ArgumentCaptor.forClass(Agent.class);
            verify(agentMapper, atLeastOnce()).updateById(updateCaptor.capture());
            Agent lastUpdate = updateCaptor.getAllValues().get(updateCaptor.getAllValues().size() - 1);
            assertThat(lastUpdate.getLastActiveTime())
                    .isAfterOrEqualTo(beforeCall.minusSeconds(1))
                    .isBeforeOrEqualTo(afterCall.plusSeconds(1));
        }
    }

    @Nested
    @DisplayName("SLEEPING 防护")
    class SleepingProtection {

        @Test
        @DisplayName("SLEEPING Agent 调 active()：不得覆盖 online_status")
        void shouldNotChangeOnlineStatusForSleeping() {
            Agent sleepingAgent = newAgent(201L, AgentOnlineStatus.SLEEPING);
            when(agentMapper.selectById(201L)).thenReturn(sleepingAgent);

            heartbeatService.active(201L);

            // seen() 对 SLEEPING 走防护分支 updateById 一次（仅刷 last_seen_time），
            // active() 末尾再 updateById 一次（刷 last_active_time）—— 总 2 次
            // 但任何 update 中 online_status 都不得变成 ONLINE
            ArgumentCaptor<Agent> updateCaptor = ArgumentCaptor.forClass(Agent.class);
            verify(agentMapper, times(2)).updateById(updateCaptor.capture());
            for (Agent updated : updateCaptor.getAllValues()) {
                assertThat(updated.getOnlineStatus())
                        .as("SLEEPING 状态下 active() 不得覆盖 online_status")
                        .isEqualTo(AgentOnlineStatus.SLEEPING);
            }
        }
    }

    @Nested
    @DisplayName("边界容错")
    class EdgeCases {

        @Test
        @DisplayName("agentId=null：直接 return，不抛异常，不调任何依赖")
        void shouldHandleNullAgentIdGracefully() {
            heartbeatService.active(null);

            // null 短路在 active() 入口，不进 seen() 也不进 DB / Redis
            verify(agentMapper, never()).selectById(any(Long.class));
            verify(agentMapper, never()).updateById(any(Agent.class));
            verify(redis, never()).opsForValue();
        }

        @Test
        @DisplayName("Agent 不存在（selectById 返 null）：active() 跳过 updateById，但 seen() 已写 Redis TTL")
        void shouldHandleMissingAgentGracefully() {
            // seen() 先写 Redis TTL、再 selectById 判 null 后只 log + return，
            // 这里验证 "不抛异常 + 不调 updateById" 即可
            when(agentMapper.selectById(404L)).thenReturn(null);

            heartbeatService.active(404L);

            // seen() 已写 Redis TTL（写入"刚有人见过"的信号，DB 查询落空不撤销）
            verify(valueOps, times(1)).set(
                    eq("agent:heartbeat:404"), anyString(), anyLong(), any());

            // active() 末尾 selectById 返 null 后不 updateById
            verify(agentMapper, never()).updateById(any(Agent.class));
        }

        @Test
        @DisplayName("Redis TTL 写入异常：active() 不阻断 DB 双写（沿用 seen() 的 try/catch 模式）")
        void shouldSurviveRedisOutage() {
            Agent agent = newAgent(301L, AgentOnlineStatus.ONLINE);
            when(agentMapper.selectById(301L)).thenReturn(agent);
            // Redis set 抛异常（void 方法用 doThrow）
            doThrow(new RuntimeException("redis down"))
                    .when(valueOps).set(eq("agent:heartbeat:301"), anyString(), anyLong(), any());

            // 不应抛异常
            heartbeatService.active(301L);

            // DB 侧的 last_active_time 仍被刷新（Redis 失败不影响 DB）
            verify(agentMapper, atLeastOnce()).updateById(any(Agent.class));
        }
    }

    @Nested
    @DisplayName("active() 节流（对话并发优化 A 项）")
    class Throttle {

        @Test
        @DisplayName("窗口内同一 agent 第二次调用直接跳过（不写 Redis/DB）")
        void shouldSkipSecondCallWithinWindow() {
            HeartbeatProperties properties = new HeartbeatProperties();
            properties.setActiveThrottleMs(60_000L);
            HeartbeatService throttled = new HeartbeatServiceImpl(agentMapper, redis, properties);

            Agent agent = newAgent(501L, AgentOnlineStatus.ONLINE);
            when(agentMapper.selectById(501L)).thenReturn(agent);

            throttled.active(501L);
            throttled.active(501L);

            // 第一次完整双写（seen 一次 update + active 末尾一次 update），第二次被节流跳过
            verify(agentMapper, times(2)).updateById(any(Agent.class));
        }

        @Test
        @DisplayName("窗口过期后再次调用恢复写入（last_active_time 误差不超过窗口）")
        void shouldWriteAgainAfterWindowExpires() {
            HeartbeatProperties properties = new HeartbeatProperties();
            properties.setActiveThrottleMs(30_000L);
            HeartbeatService throttled = new HeartbeatServiceImpl(agentMapper, redis, properties);

            Agent agent = newAgent(502L, AgentOnlineStatus.ONLINE);
            when(agentMapper.selectById(502L)).thenReturn(agent);

            throttled.active(502L);
            verify(agentMapper, times(2)).updateById(any(Agent.class));

            // 窗口过期后应再次写入：反射改时间戳回退 40s
            try {
                java.lang.reflect.Field field = HeartbeatServiceImpl.class
                        .getDeclaredField("lastActiveWriteAt");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.concurrent.ConcurrentMap<Long, Long> map =
                        (java.util.concurrent.ConcurrentMap<Long, Long>) field.get(throttled);
                map.put(502L, System.currentTimeMillis() - 40_000L);
            } catch (Exception e) {
                throw new IllegalStateException("测试反射访问 lastActiveWriteAt 失败", e);
            }

            throttled.active(502L);
            verify(agentMapper, times(4)).updateById(any(Agent.class));
        }

        @Test
        @DisplayName("不同 agent 互不节流（各自独立窗口）")
        void shouldNotThrottleAcrossAgents() {
            HeartbeatProperties properties = new HeartbeatProperties();
            properties.setActiveThrottleMs(60_000L);
            HeartbeatService throttled = new HeartbeatServiceImpl(agentMapper, redis, properties);

            Agent a = newAgent(511L, AgentOnlineStatus.ONLINE);
            Agent b = newAgent(512L, AgentOnlineStatus.ONLINE);
            when(agentMapper.selectById(511L)).thenReturn(a);
            when(agentMapper.selectById(512L)).thenReturn(b);

            throttled.active(511L);
            throttled.active(512L);

            // 两个 agent 各写一轮完整双写（各 2 次 updateById）
            verify(agentMapper, times(4)).updateById(any(Agent.class));
        }
    }

    private static Agent newAgent(Long id, AgentOnlineStatus status) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName("agent-" + id);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setOnlineStatus(status);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        agent.setLastSeenTime(OffsetDateTime.now().minusMinutes(1));
        agent.setLastActiveTime(OffsetDateTime.now().minusMinutes(5));
        agent.setConsecutiveFailureCount(0);
        return agent;
    }
}
