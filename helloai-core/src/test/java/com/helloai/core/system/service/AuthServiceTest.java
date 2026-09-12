package com.helloai.core.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单测（Sa-Token 会话化）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>登录成功后调用 {@code StpUtil.login} 建立会话，返回 token + 用户信息</li>
 *   <li>密码错误 / 用户不存在 → 抛 BizException 且不建立会话</li>
 *   <li>token 校验：{@code StpUtil.getLoginIdByToken} 命中 → 回读用户返回会话</li>
 *   <li>token 未登录（NotLoginException）→ 401；对应用户缺失/禁用 → 401</li>
 *   <li>登出调用 {@code StpUtil.logoutByTokenValue}</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService Sa-Token 会话")
class AuthServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(sysUserMapper, redis);
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
        @DisplayName("登录成功后建立 Sa-Token 会话并返回 token")
        void adminLogin_success_shouldLoginAndReturnToken() {
            when(sysUserMapper.selectOne(any())).thenReturn(newActiveUser("pass123"));

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.login(any())).thenAnswer(inv -> null);
                stp.when(StpUtil::getTokenValue).thenReturn("tok-1");

                AuthService.AdminSession session = authService.adminLogin("admin", "pass123");

                assertThat(session.token()).isEqualTo("tok-1");
                assertThat(session.id()).isEqualTo(1001L);
                stp.verify(() -> StpUtil.login(1001L));
            }
        }

        @Test
        @DisplayName("密码错误应抛 BizException 且不建会话")
        void adminLogin_wrongPassword_shouldThrowAndSkipLogin() {
            when(sysUserMapper.selectOne(any())).thenReturn(newActiveUser("pass123"));

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                assertThrows(BizException.class, () -> authService.adminLogin("admin", "wrong"));
                stp.verify(() -> StpUtil.login(any()), never());
            }
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
        @DisplayName("token 命中应回读用户并返回会话")
        void validateAdminToken_hit_shouldReturnSession() {
            when(sysUserMapper.selectById(1001L)).thenReturn(newActiveUser("pass123"));

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.getLoginIdByToken("tok-1")).thenReturn(1001L);

                AuthService.AdminSession session = authService.validateAdminToken("tok-1");

                assertThat(session.id()).isEqualTo(1001L);
                assertThat(session.username()).isEqualTo("admin");
            }
        }

        @Test
        @DisplayName("token 未登录应抛 401")
        void validateAdminToken_notLogin_shouldThrow401() {
            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.getLoginIdByToken(anyString()))
                        .thenReturn(null);

                BizException ex = assertThrows(BizException.class,
                        () -> authService.validateAdminToken("bad-tok"));
                assertEquals(401, ex.getCode());
            }
        }

        @Test
        @DisplayName("对应用户不存在应抛 401")
        void validateAdminToken_userMissing_shouldThrow401() {
            when(sysUserMapper.selectById(1001L)).thenReturn(null);

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.getLoginIdByToken("tok-1")).thenReturn(1001L);

                BizException ex = assertThrows(BizException.class,
                        () -> authService.validateAdminToken("tok-1"));
                assertEquals(401, ex.getCode());
            }
        }
    }

    @Nested
    @DisplayName("存量会话无缝迁移")
    class LegacySessionMigration {

        @Test
        @DisplayName("Sa-Token 未命中但旧 Redis 会话存在 → 以原 token 重建会话并删除旧 key")
        void legacyHit_shouldRebuildWithSameToken() {
            when(valueOps.get("auth:admin:token:legacy-tok"))
                    .thenReturn("{\"token\":\"legacy-tok\",\"id\":1001,\"username\":\"admin\",\"displayName\":\"管理员\",\"role\":\"ADMIN\"}");
            when(sysUserMapper.selectById(1001L)).thenReturn(newActiveUser("pass123"));

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.getLoginIdByToken("legacy-tok"))
                        .thenReturn(null);
                stp.when(() -> StpUtil.login(any(), any(cn.dev33.satoken.stp.parameter.SaLoginParameter.class)))
                        .thenAnswer(inv -> null);

                AuthService.AdminSession session = authService.validateAdminToken("legacy-tok");

                assertThat(session.id()).isEqualTo(1001L);
                // 原 token 值保留 → 前端零改动
                assertThat(session.token()).isEqualTo("legacy-tok");
                verify(redis).delete("auth:admin:token:legacy-tok");
                stp.verify(() -> StpUtil.login(any(), any(cn.dev33.satoken.stp.parameter.SaLoginParameter.class)));
            }
        }

        @Test
        @DisplayName("旧会话 JSON 损坏 → 清理旧 key 并抛 401")
        void legacyCorrupted_shouldCleanAndThrow401() {
            when(valueOps.get("auth:admin:token:bad-tok")).thenReturn("{not-json");

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.getLoginIdByToken("bad-tok"))
                        .thenReturn(null);

                BizException ex = assertThrows(BizException.class,
                        () -> authService.validateAdminToken("bad-tok"));
                assertEquals(401, ex.getCode());
                verify(redis).delete("auth:admin:token:bad-tok");
            }
        }

        @Test
        @DisplayName("旧会话与 Sa-Token 均未命中 → 401")
        void legacyMiss_shouldThrow401() {
            when(valueOps.get("auth:admin:token:ghost")).thenReturn(null);

            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                stp.when(() -> StpUtil.getLoginIdByToken("ghost"))
                        .thenReturn(null);

                BizException ex = assertThrows(BizException.class,
                        () -> authService.validateAdminToken("ghost"));
                assertEquals(401, ex.getCode());
                verify(redis, never()).delete(anyString());
            }
        }
    }

    @Nested
    @DisplayName("管理员登出")
    class AdminLogout {

        @Test
        @DisplayName("登出应调用按 token 值销毁会话")
        void adminLogout_shouldLogoutByTokenValue() {
            try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
                authService.adminLogout("tok-1");
                stp.verify(() -> StpUtil.logoutByTokenValue("tok-1"));
            }
        }
    }
}