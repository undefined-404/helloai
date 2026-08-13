package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.impl.AuthServiceImpl;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单测（管理员会话 Redis 化，2026-07-28 落地）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>登录成功后会话以 JSON 写入 Redis（key 前缀 auth:admin:token:，TTL 8h）</li>
 *   <li>校验命中后滑动续期（expire 重置 TTL）</li>
 *   <li>token 未命中 / 缓存值损坏 → 401，损坏 key 被清理</li>
 *   <li>登出删除 Redis key</li>
 *   <li>Agent API Key 校验行为不变（直查 DB，无效 401 / 禁用 403）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService 管理员会话 Redis 化")
class AuthServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(sysUserMapper, agentMapper, redis);
        // LENIENT 模式：部分用例不触 Redis，此 stubbing 不是"未使用"而是被跳过
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private SysUser newActiveUser(String rawPassword) {
        SysUser user = new SysUser();
        user.setId(1001L);
        user.setUsername("admin");
        user.setNickname("管理员");
        user.setRole("ADMIN");
        user.setStatus("ACTIVE");
        user.setPassword(new BCryptPasswordEncoder().encode(rawPassword));
        return user;
    }

    @Nested
    @DisplayName("管理员登录")
    class AdminLogin {

        @Test
        @DisplayName("登录成功后会话 JSON 写入 Redis，key 前缀 + TTL 符合约定")
        void adminLogin_success_shouldWriteSessionToRedis() {
            when(sysUserMapper.selectOne(any())).thenReturn(newActiveUser("pass123"));

            AuthService.AdminSession session = authService.adminLogin("admin", "pass123");

            assertThat(session.token()).isNotBlank();
            assertThat(session.id()).isEqualTo(1001L);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
            verify(valueOps).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

            assertThat(keyCaptor.getValue())
                    .isEqualTo(AuthService.ADMIN_TOKEN_KEY_PREFIX + session.token());
            assertThat(valueCaptor.getValue()).contains("\"username\":\"admin\"");
            assertThat(ttlCaptor.getValue()).isEqualTo(AuthService.ADMIN_TOKEN_TTL);
        }

        @Test
        @DisplayName("密码错误应抛 BizException 且不写 Redis")
        void adminLogin_wrongPassword_shouldThrowAndSkipRedis() {
            when(sysUserMapper.selectOne(any())).thenReturn(newActiveUser("pass123"));

            assertThrows(BizException.class, () -> authService.adminLogin("admin", "wrong"));

            verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("用户不存在应抛 BizException")
        void adminLogin_userNotFound_shouldThrow() {
            when(sysUserMapper.selectOne(any())).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> authService.adminLogin("ghost", "pass123"));
            assertEquals("用户不存在或已禁用", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("管理员 token 校验")
    class ValidateAdminToken {

        @Test
        @DisplayName("Redis 命中应返回会话并滑动续期")
        void validateAdminToken_hit_shouldReturnSessionAndRenewTtl() {
            String token = "tok-1";
            String key = AuthService.ADMIN_TOKEN_KEY_PREFIX + token;
            when(valueOps.get(key)).thenReturn(
                    "{\"token\":\"tok-1\",\"id\":1001,\"username\":\"admin\","
                            + "\"displayName\":\"管理员\",\"role\":\"ADMIN\"}");

            AuthService.AdminSession session = authService.validateAdminToken(token);

            assertThat(session.id()).isEqualTo(1001L);
            assertThat(session.username()).isEqualTo("admin");
            verify(redis).expire(key, AuthService.ADMIN_TOKEN_TTL);
        }

        @Test
        @DisplayName("Redis 未命中应抛 401")
        void validateAdminToken_miss_shouldThrow401() {
            when(valueOps.get(anyString())).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> authService.validateAdminToken("expired-token"));
            assertEquals(401, ex.getCode());
        }

        @Test
        @DisplayName("缓存值损坏应清理 key 并抛 401")
        void validateAdminToken_corruptValue_shouldEvictAndThrow401() {
            String token = "tok-bad";
            String key = AuthService.ADMIN_TOKEN_KEY_PREFIX + token;
            when(valueOps.get(key)).thenReturn("not-a-json");

            BizException ex = assertThrows(BizException.class,
                    () -> authService.validateAdminToken(token));

            assertEquals(401, ex.getCode());
            verify(redis).delete(key);
            verify(redis, never()).expire(anyString(), any(Duration.class));
        }
    }

    @Nested
    @DisplayName("管理员登出")
    class AdminLogout {

        @Test
        @DisplayName("登出应删除 Redis 会话 key")
        void adminLogout_shouldDeleteRedisKey() {
            authService.adminLogout("tok-1");

            verify(redis).delete(AuthService.ADMIN_TOKEN_KEY_PREFIX + "tok-1");
        }
    }

    @Nested
    @DisplayName("Agent API Key 校验（行为不变）")
    class ValidateAgentKey {

        @Test
        @DisplayName("无效 API Key 应抛 401")
        void validateAgentKey_invalid_shouldThrow401() {
            when(agentMapper.selectOne(any())).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> authService.validateAgentKey("bad-key"));
            assertEquals(401, ex.getCode());
        }

        @Test
        @DisplayName("已禁用 Agent 应抛 403")
        void validateAgentKey_disabled_shouldThrow403() {
            Agent agent = new Agent();
            agent.setId(2001L);
            agent.setStatus(AgentStatus.DISABLED);
            when(agentMapper.selectOne(any())).thenReturn(agent);

            BizException ex = assertThrows(BizException.class,
                    () -> authService.validateAgentKey("disabled-key"));
            assertEquals(403, ex.getCode());
        }
    }
}
