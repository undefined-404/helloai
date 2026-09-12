package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysRole;
import com.helloai.core.system.entity.SysRolePermission;
import com.helloai.core.system.entity.SysUserRole;
import com.helloai.core.system.mapper.SysRoleMapper;
import com.helloai.core.system.mapper.SysRolePermissionMapper;
import com.helloai.core.system.mapper.SysUserRoleMapper;
import com.helloai.core.system.service.impl.SysRoleServiceImpl;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysRoleServiceImpl 单测（RBAC 角色服务核心不变量）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>角色编码唯一：重复 code 建角色 → BizException</li>
 *   <li>内置角色（SUPER_ADMIN / ADMIN）不可删除</li>
 *   <li>SUPER_ADMIN 为全权限角色，不可修改权限绑定</li>
 *   <li>角色-权限 / 用户-角色关联：先删后插，全量覆盖</li>
 * </ol>
 *
 * <p>{@code ServiceImpl.baseMapper} 为父类受保护字段（无公开 setter），测试用反射注入 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysRoleServiceImpl RBAC 角色服务")
class SysRoleServiceImplTest {

    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    private SysRoleServiceImpl roleService;

    @BeforeEach
    void setUp() throws Exception {
        roleService = new SysRoleServiceImpl(sysRolePermissionMapper, sysUserRoleMapper);
        injectBaseMapper(roleService, sysRoleMapper);
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

    private SysRole newRole(Long id, String code, String status) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        role.setName(code);
        role.setStatus(status);
        return role;
    }

    @Nested
    @DisplayName("角色创建")
    class CreateRole {

        /**
         * createRole 内部走 {@code lambdaQuery()}（MyBatis-Plus 链式查询，lambda 列解析依赖运行环境），
         * 单测以 spy 拦截该链式调用，仅验证重复 code 的业务规则分支。
         */
        private SysRoleServiceImpl spyServiceWithCount(Long count) throws Exception {
            SysRoleServiceImpl spy = spy(new SysRoleServiceImpl(sysRolePermissionMapper, sysUserRoleMapper));
            injectBaseMapper(spy, sysRoleMapper);
            LambdaQueryChainWrapper<SysRole> chain = mock(LambdaQueryChainWrapper.class);
            doReturn(chain).when(spy).lambdaQuery();
            when(chain.eq(any(), any())).thenReturn(chain);
            when(chain.count()).thenReturn(count);
            return spy;
        }

        @Test
        @DisplayName("code 重复应抛 BizException")
        void createRole_duplicateCode_shouldThrow() throws Exception {
            SysRoleServiceImpl spy = spyServiceWithCount(1L);

            BizException ex = assertThrows(BizException.class,
                    () -> spy.createRole("ADMIN", "管理员", null, "ACTIVE", 0));
            assertThat(ex.getMessage()).contains("已存在");
            verify(sysRoleMapper, never()).insert(any(SysRole.class));
        }

        @Test
        @DisplayName("code 唯一应创建成功并落库")
        void createRole_success_shouldInsert() throws Exception {
            SysRoleServiceImpl spy = spyServiceWithCount(0L);

            SysRole role = spy.createRole("OPS", "运维", "日常运维", "ACTIVE", 3);

            assertThat(role.getCode()).isEqualTo("OPS");
            assertThat(role.getStatus()).isEqualTo("ACTIVE");
            verify(sysRoleMapper).insert(role);
        }
    }

    @Nested
    @DisplayName("角色删除")
    class DeleteRole {

        @Test
        @DisplayName("内置角色不可删除")
        void deleteRole_builtin_shouldThrow() {
            when(sysRoleMapper.selectById(1L)).thenReturn(newRole(1L, "SUPER_ADMIN", "ACTIVE"));

            BizException ex = assertThrows(BizException.class, () -> roleService.deleteRole(1L));
            assertThat(ex.getMessage()).contains("内置角色不可删除");
            verify(sysRoleMapper, never()).deleteById(1L);
        }

        @Test
        @DisplayName("非内置角色删除并清理权限关联")
        void deleteRole_normal_shouldRemoveLinks() {
            when(sysRoleMapper.selectById(9L)).thenReturn(newRole(9L, "OPS", "ACTIVE"));
            when(sysRolePermissionMapper.delete(any())).thenReturn(1);

            roleService.deleteRole(9L);

            verify(sysRoleMapper).deleteById(9L);
            verify(sysRolePermissionMapper).delete(any());
        }
    }

    @Nested
    @DisplayName("角色-权限绑定")
    class AssignPermissions {

        @Test
        @DisplayName("SUPER_ADMIN 不可修改权限")
        void assignPermissions_superAdmin_shouldThrow() {
            when(sysRoleMapper.selectById(1L)).thenReturn(newRole(1L, "SUPER_ADMIN", "ACTIVE"));

            BizException ex = assertThrows(BizException.class,
                    () -> roleService.assignPermissions(1L, List.of(1L, 2L)));
            assertThat(ex.getMessage()).contains("超级管理员");
            verify(sysRolePermissionMapper, never()).delete(any());
        }

        @Test
        @DisplayName("普通角色先删后插全量覆盖")
        void assignPermissions_normal_shouldReplaceAll() {
            when(sysRoleMapper.selectById(2L)).thenReturn(newRole(2L, "ADMIN", "ACTIVE"));
            when(sysRolePermissionMapper.delete(any())).thenReturn(2);

            roleService.assignPermissions(2L, List.of(10L, 11L));

            verify(sysRolePermissionMapper).delete(any());
            verify(sysRolePermissionMapper, times(2)).insert(any(SysRolePermission.class));
        }

        @Test
        @DisplayName("差异更新：仅插入新增项、删除移除项（BASE-2.3）")
        void assignPermissions_withLast_shouldDiffInsertDelete() {
            when(sysRoleMapper.selectById(2L)).thenReturn(newRole(2L, "ADMIN", "ACTIVE"));
            when(sysRolePermissionMapper.insert(any(SysRolePermission.class))).thenReturn(1);
            when(sysRolePermissionMapper.delete(any())).thenReturn(1);

            // last=[10,11] → target=[10,11,12]：仅新增 12，不触发全量删插
            roleService.assignPermissions(2L, List.of(10L, 11L, 12L), List.of(10L, 11L));

            verify(sysRolePermissionMapper, times(1)).insert(any(SysRolePermission.class));
            verify(sysRolePermissionMapper, never()).delete(any());
        }

        @Test
        @DisplayName("差异更新：last 含 target 无变化→零写（BASE-2.3）")
        void assignPermissions_withLast_noChange_shouldNoWrite() {
            when(sysRoleMapper.selectById(2L)).thenReturn(newRole(2L, "ADMIN", "ACTIVE"));

            roleService.assignPermissions(2L, List.of(10L, 11L), List.of(10L, 11L));

            verify(sysRolePermissionMapper, never()).insert(any(SysRolePermission.class));
            verify(sysRolePermissionMapper, never()).delete(any());
        }

        @Test
        @DisplayName("差异更新：撤销项触发定向删除，保留项不重复插（BASE-2.3）")
        void assignPermissions_withLast_shouldRemoveOnly() {
            when(sysRoleMapper.selectById(2L)).thenReturn(newRole(2L, "ADMIN", "ACTIVE"));
            when(sysRolePermissionMapper.insert(any(SysRolePermission.class))).thenReturn(1);
            when(sysRolePermissionMapper.delete(any())).thenReturn(1);

            // last=[10,11,12] → target=[10,11]：仅删除 12
            roleService.assignPermissions(2L, List.of(10L, 11L), List.of(10L, 11L, 12L));

            verify(sysRolePermissionMapper, never()).insert(any(SysRolePermission.class));
            verify(sysRolePermissionMapper).delete(any());
        }

        @Test
        @DisplayName("SUPER_ADMIN 不可修改权限（差异路径同样拒绝）")
        void assignPermissions_diff_superAdmin_shouldThrow() {
            when(sysRoleMapper.selectById(1L)).thenReturn(newRole(1L, "SUPER_ADMIN", "ACTIVE"));

            BizException ex = assertThrows(BizException.class,
                    () -> roleService.assignPermissions(1L, List.of(1L), List.of(2L)));
            assertThat(ex.getMessage()).contains("超级管理员");
            verify(sysRolePermissionMapper, never()).insert(any(SysRolePermission.class));
        }
    }

    @Nested
    @DisplayName("用户-角色分配")
    class AssignUserRoles {

        @Test
        @DisplayName("先删后插全量覆盖用户角色")
        void assignUserRoles_shouldReplaceAll() {
            when(sysUserRoleMapper.delete(any())).thenReturn(1);

            roleService.assignUserRoles(1001L, List.of(2L, 3L));

            verify(sysUserRoleMapper).delete(any());
            verify(sysUserRoleMapper, times(2)).insert(any(SysUserRole.class));
        }

        @Test
        @DisplayName("空角色列表仅清空不插入")
        void assignUserRoles_empty_shouldOnlyDelete() {
            when(sysUserRoleMapper.delete(any())).thenReturn(1);

            roleService.assignUserRoles(1001L, List.of());

            verify(sysUserRoleMapper).delete(any());
            verify(sysUserRoleMapper, never()).insert(any(SysUserRole.class));
        }
    }
}
