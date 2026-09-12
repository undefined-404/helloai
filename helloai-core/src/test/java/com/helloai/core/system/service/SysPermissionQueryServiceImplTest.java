package com.helloai.core.system.service;

import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysRole;
import com.helloai.core.system.entity.SysRolePermission;
import com.helloai.core.system.entity.SysUserRole;
import com.helloai.core.system.mapper.SysPermissionMapper;
import com.helloai.core.system.mapper.SysRoleMapper;
import com.helloai.core.system.mapper.SysRolePermissionMapper;
import com.helloai.core.system.mapper.SysUserRoleMapper;
import com.helloai.core.system.service.impl.SysPermissionQueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SysPermissionQueryServiceImpl 单测（RBAC 读侧：角色码 / 权限码聚合）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>SUPER_ADMIN 角色 → 权限码返回全权限通配符 "*"（前端与 @SaCheckPermission 双端识别）</li>
 *   <li>普通角色 → 按 role → role_permission → permission 聚合去重权限码</li>
 *   <li>无角色 / 角色全部禁用 → 空列表</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysPermissionQueryServiceImpl RBAC 权限查询")
class SysPermissionQueryServiceImplTest {

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;
    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysRolePermissionMapper sysRolePermissionMapper;
    @Mock
    private SysPermissionMapper sysPermissionMapper;

    private SysPermissionQueryServiceImpl queryService;

    @BeforeEach
    void setUp() {
        queryService = new SysPermissionQueryServiceImpl(
                sysUserRoleMapper, sysRoleMapper, sysRolePermissionMapper, sysPermissionMapper);
    }

    private SysRole newRole(Long id, String code, String status) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        role.setStatus(status);
        return role;
    }

    private SysUserRole newUserRole(Long roleId) {
        SysUserRole ur = new SysUserRole();
        ur.setRoleId(roleId);
        return ur;
    }

    private SysPermission newPermission(Long id, String code) {
        SysPermission p = new SysPermission();
        p.setId(id);
        p.setCode(code);
        return p;
    }

    private SysPermission menu(Long id, String code, Long parentId) {
        SysPermission p = newPermission(id, code);
        p.setType("MENU");
        p.setParentId(parentId);
        return p;
    }

    private SysRolePermission rolePermission(Long permissionId) {
        SysRolePermission rp = new SysRolePermission();
        rp.setPermissionId(permissionId);
        return rp;
    }

    @Nested
    @DisplayName("权限码聚合")
    class PermissionCode {

        @Test
        @DisplayName("SUPER_ADMIN 返回全权限通配符 *")
        void superAdmin_shouldReturnWildcard() {
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(newUserRole(1L)));
            when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(newRole(1L, "SUPER_ADMIN", "ACTIVE")));

            assertThat(queryService.listPermissionCode(1001L)).containsExactly("*");
        }

        @Test
        @DisplayName("普通角色按关联表聚合权限码并去重")
        void normalUser_shouldAggregateDistinctCodes() {
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(newUserRole(2L)));
            when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(newRole(2L, "ADMIN", "ACTIVE")));
            when(sysRolePermissionMapper.selectList(any()))
                    .thenReturn(List.of(rolePermission(1L), rolePermission(2L), rolePermission(1L)));
            when(sysPermissionMapper.selectBatchIds(any()))
                    .thenReturn(List.of(newPermission(1L, "task:view"), newPermission(2L, "agent:view")));

            List<String> codes = queryService.listPermissionCode(1001L);

            assertThat(codes).containsExactlyInAnyOrder("task:view", "agent:view");
        }

        @Test
        @DisplayName("无角色返回空列表")
        void noRoles_shouldReturnEmpty() {
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of());

            assertThat(queryService.listPermissionCode(1001L)).isEmpty();
        }

        @Test
        @DisplayName("角色全部禁用返回空列表")
        void disabledRoles_shouldReturnEmpty() {
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(newUserRole(5L)));
            when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(newRole(5L, "OPS", "DISABLED")));

            assertThat(queryService.listPermissionCode(1001L)).isEmpty();
        }

        private SysRolePermission rolePermission(Long permissionId) {
            SysRolePermission rp = new SysRolePermission();
            rp.setPermissionId(permissionId);
            return rp;
        }
    }

    @Nested
    @DisplayName("角色码查询")
    class RoleCode {

        @Test
        @DisplayName("返回用户全部激活角色码")
        void shouldReturnRoleCodes() {
            when(sysUserRoleMapper.selectList(any()))
                    .thenReturn(List.of(newUserRole(1L), newUserRole(2L)));
            when(sysRoleMapper.selectBatchIds(any()))
                    .thenReturn(List.of(newRole(1L, "SUPER_ADMIN", "ACTIVE"), newRole(2L, "ADMIN", "ACTIVE")));

            assertThat(queryService.listRoleCode(1001L))
                    .containsExactlyInAnyOrder("SUPER_ADMIN", "ADMIN");
        }
    }

    @Nested
    @DisplayName("菜单树构建")
    class MenuTree {

        @Test
        @DisplayName("SUPER_ADMIN 全量菜单树（含系统设置父子层级）")
        void superAdmin_shouldReturnFullTree() {
            when(sysPermissionMapper.selectList(any())).thenReturn(List.of(
                    menu(1L, "dashboard:view", null),
                    menu(17L, "settings:view", null),
                    menu(24L, "user:view", 17L)));
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(newUserRole(1L)));
            when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(newRole(1L, "SUPER_ADMIN", "ACTIVE")));

            List<SysPermission> tree = queryService.listMenuTree(1001L);

            assertThat(tree).hasSize(2);
            SysPermission settings = tree.stream()
                    .filter(p -> "settings:view".equals(p.getCode()))
                    .findFirst()
                    .orElseThrow();
            assertThat(settings.getChildren())
                    .extracting(SysPermission::getCode)
                    .containsExactly("user:view");
        }

        @Test
        @DisplayName("普通用户：仅可见有权限码的菜单；无可见子节点的父菜单被剔除")
        void normalUser_shouldFilterAndDropEmptyParent() {
            when(sysPermissionMapper.selectList(any())).thenReturn(List.of(
                    menu(1L, "dashboard:view", null),
                    menu(2L, "task:view", null),
                    menu(17L, "settings:view", null),
                    menu(24L, "user:view", 17L)));
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(newUserRole(2L)));
            when(sysRoleMapper.selectBatchIds(any())).thenReturn(List.of(newRole(2L, "ADMIN", "ACTIVE")));
            // ADMIN 仅绑定 dashboard:view / task:view，无 user:view → 系统设置无可视子节点
            when(sysRolePermissionMapper.selectList(any()))
                    .thenReturn(List.of(rolePermission(1L), rolePermission(2L)));
            when(sysPermissionMapper.selectBatchIds(any()))
                    .thenReturn(List.of(newPermission(1L, "dashboard:view"), newPermission(2L, "task:view")));

            List<SysPermission> tree = queryService.listMenuTree(1001L);

            assertThat(tree).extracting(SysPermission::getCode)
                    .containsExactly("dashboard:view", "task:view");
        }

        @Test
        @DisplayName("无任何权限的用户返回空菜单树")
        void noPermission_shouldReturnEmptyTree() {
            when(sysPermissionMapper.selectList(any())).thenReturn(List.of(
                    menu(1L, "dashboard:view", null),
                    menu(17L, "settings:view", null)));
            when(sysUserRoleMapper.selectList(any())).thenReturn(List.of());

            assertThat(queryService.listMenuTree(1001L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("全量权限树（管理侧，BASE-2.1）")
    class PermissionTree {

        @Test
        @DisplayName("MENU 挂层级 + API 平铺根级叶子")
        void shouldBuildFullTree() {
            when(sysPermissionMapper.selectList(any())).thenReturn(List.of(
                    menu(1L, "dashboard:view", null),
                    menu(17L, "settings:view", null),
                    menu(24L, "user:view", 17L),
                    api(30L, "user:delete")));

            List<SysPermission> tree = queryService.listPermissionTree();

            assertThat(tree).hasSize(3); // dashboard / settings(含子) / user:delete
            SysPermission settings = tree.stream()
                    .filter(p -> "settings:view".equals(p.getCode()))
                    .findFirst()
                    .orElseThrow();
            assertThat(settings.getChildren())
                    .extracting(SysPermission::getCode)
                    .containsExactly("user:view");
        }

        @Test
        @DisplayName("父节点缺失的孤儿 MENU 兜底平铺为根级")
        void orphanMenu_shouldFallbackToRoot() {
            when(sysPermissionMapper.selectList(any())).thenReturn(List.of(
                    menu(50L, "orphan:view", 999L)));

            List<SysPermission> tree = queryService.listPermissionTree();

            assertThat(tree).hasSize(1);
            assertThat(tree.get(0).getCode()).isEqualTo("orphan:view");
        }

        private SysPermission api(Long id, String code) {
            SysPermission p = newPermission(id, code);
            p.setType("API");
            return p;
        }
    }
}
