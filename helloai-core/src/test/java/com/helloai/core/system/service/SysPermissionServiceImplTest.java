package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysRolePermission;
import com.helloai.core.system.mapper.SysPermissionMapper;
import com.helloai.core.system.mapper.SysRolePermissionMapper;
import com.helloai.core.system.service.impl.SysPermissionServiceImpl;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysPermissionServiceImpl 单测（RBAC 权限/菜单管理写侧，BASE-2.1）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>code 唯一：重复 code 创建 → BizException</li>
 *   <li>type 仅 MENU / API，非法值拒绝</li>
 *   <li>MENU 挂父校验：父不存在或父非 MENU → 拒绝</li>
 *   <li>删除守卫：存在子菜单 / 被角色引用 → 拒绝</li>
 *   <li>API 类型更新时清空层级字段</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysPermissionServiceImpl RBAC 权限/菜单管理")
class SysPermissionServiceImplTest {

    @Mock
    private SysPermissionMapper sysPermissionMapper;
    @Mock
    private SysRolePermissionMapper sysRolePermissionMapper;

    private SysPermissionServiceImpl permissionService;

    @BeforeEach
    void setUp() throws Exception {
        permissionService = new SysPermissionServiceImpl(sysRolePermissionMapper);
        injectBaseMapper(permissionService, sysPermissionMapper);
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

    private SysPermission newPerm(Long id, String code, String type, Long parentId) {
        SysPermission p = new SysPermission();
        p.setId(id);
        p.setCode(code);
        p.setType(type);
        p.setParentId(parentId);
        return p;
    }

    private SysPermission newReq(String code, String type, Long parentId) {
        SysPermission p = new SysPermission();
        p.setCode(code);
        p.setName("测试权限");
        p.setType(type);
        p.setSort(1);
        p.setParentId(parentId);
        p.setPath("/test");
        p.setIcon("Menu");
        p.setComponent("test/Test");
        return p;
    }

    /** createPermission 内部走 lambdaQuery()，spy 拦截链式调用 */
    private SysPermissionServiceImpl spyWithCount(Long count) throws Exception {
        SysPermissionServiceImpl spy = spy(new SysPermissionServiceImpl(sysRolePermissionMapper));
        injectBaseMapper(spy, sysPermissionMapper);
        LambdaQueryChainWrapper<SysPermission> chain = mock(LambdaQueryChainWrapper.class);
        doReturn(chain).when(spy).lambdaQuery();
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.count()).thenReturn(count);
        return spy;
    }

    @Nested
    @DisplayName("权限/菜单创建")
    class Create {

        @Test
        @DisplayName("code 重复应抛 BizException")
        void create_duplicateCode_shouldThrow() throws Exception {
            SysPermissionServiceImpl spy = spyWithCount(1L);

            BizException ex = assertThrows(BizException.class,
                    () -> spy.createPermission(newReq("task:view", "MENU", null)));
            assertThat(ex.getMessage()).contains("已存在");
            verify(sysPermissionMapper, never()).insert(any(SysPermission.class));
        }

        @Test
        @DisplayName("type 非法应抛 BizException")
        void create_illegalType_shouldThrow() throws Exception {
            SysPermissionServiceImpl spy = spyWithCount(0L);

            BizException ex = assertThrows(BizException.class,
                    () -> spy.createPermission(newReq("test:view", "BUTTON", null)));
            assertThat(ex.getMessage()).contains("MENU / API");
        }

        @Test
        @DisplayName("MENU 挂父不存在应抛 BizException")
        void create_parentMissing_shouldThrow() throws Exception {
            SysPermissionServiceImpl spy = spyWithCount(0L);
            when(sysPermissionMapper.selectById(999L)).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> spy.createPermission(newReq("test:view", "MENU", 999L)));
            assertThat(ex.getMessage()).contains("父菜单不存在");
        }

        @Test
        @DisplayName("MENU 挂 API 父应抛 BizException")
        void create_parentNotMenu_shouldThrow() throws Exception {
            SysPermissionServiceImpl spy = spyWithCount(0L);
            when(sysPermissionMapper.selectById(18L)).thenReturn(newPerm(18L, "agent:manage", "API", null));

            BizException ex = assertThrows(BizException.class,
                    () -> spy.createPermission(newReq("test:view", "MENU", 18L)));
            assertThat(ex.getMessage()).contains("必须是菜单类型");
        }

        @Test
        @DisplayName("合法 MENU 创建成功，code 归一大写")
        void create_menu_success() throws Exception {
            SysPermissionServiceImpl spy = spyWithCount(0L);
            when(sysPermissionMapper.selectById(17L)).thenReturn(newPerm(17L, "settings:view", "MENU", null));

            SysPermission p = spy.createPermission(newReq("test:view", "menu", 17L));

            assertThat(p.getCode()).isEqualTo("TEST:VIEW");
            assertThat(p.getType()).isEqualTo("MENU");
            assertThat(p.getParentId()).isEqualTo(17L);
            verify(sysPermissionMapper).insert(p);
        }

        @Test
        @DisplayName("合法 API 创建成功且不落层级字段")
        void create_api_success() throws Exception {
            SysPermissionServiceImpl spy = spyWithCount(0L);

            SysPermission p = spy.createPermission(newReq("test:add", "API", null));

            assertThat(p.getType()).isEqualTo("API");
            assertThat(p.getPath()).isNull();
            assertThat(p.getIcon()).isNull();
            assertThat(p.getComponent()).isNull();
            verify(sysPermissionMapper).insert(p);
        }
    }

    @Nested
    @DisplayName("权限/菜单更新")
    class Update {

        @Test
        @DisplayName("id 不存在应抛 BizException")
        void update_missing_shouldThrow() {
            when(sysPermissionMapper.selectById(404L)).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> permissionService.updatePermission(404L, newReq("x:view", "MENU", null)));
            assertThat(ex.getMessage()).contains("不存在");
        }

        @Test
        @DisplayName("API 类型更新清空层级字段")
        void update_toApi_shouldClearHierarchy() {
            when(sysPermissionMapper.selectById(24L))
                    .thenReturn(newPerm(24L, "user:view", "MENU", 17L));

            SysPermission req = newReq("user:view", "API", null);
            permissionService.updatePermission(24L, req);

            SysPermission updated = verifyAndGetCaptured();
            assertThat(updated.getType()).isEqualTo("API");
            assertThat(updated.getParentId()).isNull();
            assertThat(updated.getPath()).isNull();
            assertThat(updated.getIcon()).isNull();
            assertThat(updated.getComponent()).isNull();
        }

        private SysPermission verifyAndGetCaptured() {
            org.mockito.ArgumentCaptor<SysPermission> captor =
                    org.mockito.ArgumentCaptor.forClass(SysPermission.class);
            verify(sysPermissionMapper).updateById(captor.capture());
            return captor.getValue();
        }
    }

    @Nested
    @DisplayName("权限/菜单删除")
    class Delete {

        @Test
        @DisplayName("MENU 存在子菜单应拒绝")
        void delete_menuWithChildren_shouldThrow() {
            when(sysPermissionMapper.selectById(17L))
                    .thenReturn(newPerm(17L, "settings:view", "MENU", null));
            when(sysPermissionMapper.selectCount(any())).thenReturn(2L);

            BizException ex = assertThrows(BizException.class,
                    () -> permissionService.deletePermission(17L));
            assertThat(ex.getMessage()).contains("子菜单");
            verify(sysPermissionMapper, never()).deleteById(17L);
        }

        @Test
        @DisplayName("被角色引用应拒绝")
        void delete_referencedByRole_shouldThrow() {
            when(sysPermissionMapper.selectById(30L))
                    .thenReturn(newPerm(30L, "user:delete", "API", null));
            when(sysRolePermissionMapper.selectCount(any())).thenReturn(1L);

            BizException ex = assertThrows(BizException.class,
                    () -> permissionService.deletePermission(30L));
            assertThat(ex.getMessage()).contains("角色绑定");
            verify(sysPermissionMapper, never()).deleteById(30L);
        }

        @Test
        @DisplayName("无子节点且未被引用可删除")
        void delete_ok() {
            when(sysPermissionMapper.selectById(30L))
                    .thenReturn(newPerm(30L, "user:delete", "API", null));
            when(sysRolePermissionMapper.selectCount(any())).thenReturn(0L);

            permissionService.deletePermission(30L);

            verify(sysPermissionMapper).deleteById(30L);
        }
    }
}
