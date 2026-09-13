package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysRole;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.mapper.SysUserDepartMapper;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysUserServiceImpl 单测（BASE-4.2 身份单事实源不变量）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>建号即签发角色：用户落库后按角色码解析 {@code sys_role.id} 并写入
 *       {@code sys_user_role}（不再写已退场的 {@code sys_user.role}）</li>
 *   <li>角色码不存在 → fail-close：抛 BizException 且用户不落库</li>
 *   <li>未指定角色码 → 取默认 ADMIN</li>
 * </ol>
 *
 * <p>{@code ServiceImpl.baseMapper} 为父类受保护字段（无公开 setter），测试用反射注入 mock；
 * {@code lambdaQuery()} 链式查询由 spy 拦截（lambda 列解析依赖运行环境）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysUserServiceImpl 建号与角色签发")
class SysUserServiceImplTest {

    @Mock
    private AuthService authService;
    @Mock
    private SysUserDepartMapper sysUserDepartMapper;
    @Mock
    private SysPermissionDataRuleService sysPermissionDataRuleService;
    @Mock
    private SysRoleService sysRoleService;

    private SysUserServiceImpl userService;
    private SysUserMapper sysUserMapper;

    @BeforeEach
    void setUp() throws Exception {
        userService = new SysUserServiceImpl(
                authService, sysUserDepartMapper, sysPermissionDataRuleService, sysRoleService);
        sysUserMapper = mock(SysUserMapper.class);
        injectBaseMapper(userService, sysUserMapper);
    }

    private static void injectBaseMapper(Object service, Object mapper) throws Exception {
        Class<?> clazz = service.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(service, mapper);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalStateException("baseMapper 字段未找到");
    }

    /**
     * 构造 spy：拦截用户重名校验链（{@code lambdaQuery().eq().one()}）与角色码解析链，
     * 并把 {@code save()} 打桩为「回填主键」，模拟 MyBatis-Plus 落库分配 ID。
     */
    private SysUserServiceImpl spyService(SysUser existingUser, SysRole resolvedRole) throws Exception {
        SysUserServiceImpl spy = spy(new SysUserServiceImpl(
                authService, sysUserDepartMapper, sysPermissionDataRuleService, sysRoleService));
        injectBaseMapper(spy, sysUserMapper);

        LambdaQueryChainWrapper<SysUser> userChain = mock(LambdaQueryChainWrapper.class);
        doReturn(userChain).when(spy).lambdaQuery();
        when(userChain.eq(any(), any())).thenReturn(userChain);
        when(userChain.one()).thenReturn(existingUser);

        LambdaQueryChainWrapper<SysRole> roleChain = mock(LambdaQueryChainWrapper.class);
        when(sysRoleService.lambdaQuery()).thenReturn(roleChain);
        when(roleChain.eq(any(), any())).thenReturn(roleChain);
        when(roleChain.one()).thenReturn(resolvedRole);

        doAnswer(inv -> {
            ((SysUser) inv.getArgument(0)).setId(2001L);
            return true;
        }).when(spy).save(any(SysUser.class));

        return spy;
    }

    private SysRole newRole(Long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    @Nested
    @DisplayName("建号即签发角色")
    class CreateUser {

        @Test
        @DisplayName("角色码存在：用户落库并写入 sys_user_role")
        void create_success_shouldAssignRole() throws Exception {
            SysUserServiceImpl spy = spyService(null, newRole(2L, "ADMIN"));

            SysUser user = spy.create("newuser", "pwd123", "新用户", "ADMIN", "建号备注");

            assertThat(user.getUsername()).isEqualTo("newuser");
            assertThat(user.getStatus()).isEqualTo("ACTIVE");
            assertThat(user.getRemark()).isEqualTo("建号备注");
            // 用户落库（save 在 spyService 中打桩为回填主键）
            verify(spy).save(any(SysUser.class));
            // 身份单事实源：角色经 sys_user_role 签发
            verify(sysRoleService).assignUserRoles(eq(2001L), eq(List.of(2L)));
        }

        @Test
        @DisplayName("未指定角色码：取默认 ADMIN")
        void create_blankRole_shouldFallbackToDefault() throws Exception {
            SysUserServiceImpl spy = spyService(null, newRole(2L, "ADMIN"));

            spy.create("newuser", "pwd123", "新用户", null, null);

            verify(sysRoleService).assignUserRoles(eq(2001L), eq(List.of(2L)));
        }

        @Test
        @DisplayName("角色码不存在：fail-close，抛错且用户不落库")
        void create_unknownRole_shouldFailBeforeInsert() throws Exception {
            SysUserServiceImpl spy = spyService(null, null);

            BizException ex = assertThrows(BizException.class,
                    () -> spy.create("newuser", "pwd123", "新用户", "NO_SUCH_ROLE", null));

            assertThat(ex.getMessage()).contains("角色码不存在");
            // 400：角色码为客户端输入，属参数错误而非服务端故障
            assertEquals(400, ex.getCode().intValue());
            verify(spy, never()).save(any(SysUser.class));
            verify(sysRoleService, never()).assignUserRoles(any(), any());
        }

        @Test
        @DisplayName("用户名已存在：抛错且不签发角色")
        void create_duplicateUsername_shouldThrow() throws Exception {
            SysUser existing = new SysUser();
            existing.setId(1L);
            existing.setUsername("admin");
            SysUserServiceImpl spy = spyService(existing, newRole(1L, "SUPER_ADMIN"));

            BizException ex = assertThrows(BizException.class,
                    () -> spy.create("admin", "pwd123", "管理员", "SUPER_ADMIN", null));

            assertThat(ex.getMessage()).contains("已存在");
            verify(spy, never()).save(any(SysUser.class));
            verify(sysRoleService, never()).assignUserRoles(any(), any());
        }
    }
}
