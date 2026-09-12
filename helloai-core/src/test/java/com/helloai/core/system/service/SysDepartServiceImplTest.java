package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysDepart;
import com.helloai.core.system.entity.SysUserDepart;
import com.helloai.core.system.mapper.SysDepartMapper;
import com.helloai.core.system.mapper.SysUserDepartMapper;
import com.helloai.core.system.service.impl.SysDepartServiceImpl;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysDepartServiceImpl 单测（组织架构树，BASE-3.2 / BASE-3.3）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>listTree：parent 挂接 + 孤儿兜底平铺</li>
 *   <li>删除守卫：存在子部门 / 已被用户关联 → 拒绝</li>
 *   <li>listDepartAndChildIds：含自身的后代 BFS 收集</li>
 *   <li>listUserIdsByDepartIds：按部门批量取用户（空入参返回空）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysDepartServiceImpl 部门服务")
class SysDepartServiceImplTest {

    @Mock
    private SysDepartMapper sysDepartMapper;
    @Mock
    private SysUserDepartMapper sysUserDepartMapper;

    private SysDepartServiceImpl departService;

    @BeforeEach
    void setUp() throws Exception {
        departService = new SysDepartServiceImpl(sysUserDepartMapper);
        injectBaseMapper(departService, sysDepartMapper);
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

    private SysDepart depart(Long id, String name, Long parentId) {
        SysDepart d = new SysDepart();
        d.setId(id);
        d.setName(name);
        d.setParentId(parentId);
        return d;
    }

    /** deleteDepart 内部走 baseMapper.selectCount，直接 stub */
    private SysDepartServiceImpl spyWithChildCount(Long count) throws Exception {
        SysDepartServiceImpl spy = spy(new SysDepartServiceImpl(sysUserDepartMapper));
        injectBaseMapper(spy, sysDepartMapper);
        doReturn(count).when(sysDepartMapper).selectCount(any());
        return spy;
    }

    @Nested
    @DisplayName("部门树构建")
    class Tree {

        @Test
        @DisplayName("父子挂接：子部门挂到父节点 children")
        void shouldBuildTree() {
            when(sysDepartMapper.selectList(any())).thenReturn(List.of(
                    depart(1L, "总部", null),
                    depart(2L, "研发中心", 1L),
                    depart(3L, "测试组", 2L)));

            List<SysDepart> tree = departService.listTree();

            assertThat(tree).hasSize(1);
            SysDepart root = tree.get(0);
            assertThat(root.getChildren()).hasSize(1);
            assertThat(root.getChildren().get(0).getChildren())
                    .extracting(SysDepart::getName)
                    .containsExactly("测试组");
        }

        @Test
        @DisplayName("父节点缺失的孤儿兜底平铺为根级")
        void orphan_shouldFallbackToRoot() {
            when(sysDepartMapper.selectList(any())).thenReturn(List.of(
                    depart(9L, "孤儿部门", 999L)));

            List<SysDepart> tree = departService.listTree();

            assertThat(tree).hasSize(1);
            assertThat(tree.get(0).getName()).isEqualTo("孤儿部门");
        }
    }

    @Nested
    @DisplayName("部门删除守卫")
    class Delete {

        @Test
        @DisplayName("存在子部门 → 拒绝")
        void withChildren_shouldThrow() throws Exception {
            when(sysDepartMapper.selectById(1L)).thenReturn(depart(1L, "总部", null));
            SysDepartServiceImpl spy = spyWithChildCount(2L);

            BizException ex = assertThrows(BizException.class, () -> spy.deleteDepart(1L));
            assertThat(ex.getMessage()).contains("子部门");
            verify(sysDepartMapper, never()).deleteById(1L);
        }

        @Test
        @DisplayName("已被用户关联 → 拒绝")
        void referencedByUser_shouldThrow() throws Exception {
            when(sysDepartMapper.selectById(5L)).thenReturn(depart(5L, "研发中心", null));
            SysDepartServiceImpl spy = spyWithChildCount(0L);
            when(sysUserDepartMapper.selectCount(any())).thenReturn(3L);

            BizException ex = assertThrows(BizException.class, () -> spy.deleteDepart(5L));
            assertThat(ex.getMessage()).contains("用户关联");
            verify(sysDepartMapper, never()).deleteById(5L);
        }

        @Test
        @DisplayName("无子部门且未被关联 → 删除成功")
        void canDelete() throws Exception {
            when(sysDepartMapper.selectById(7L)).thenReturn(depart(7L, "临时部门", null));
            SysDepartServiceImpl spy = spyWithChildCount(0L);
            when(sysUserDepartMapper.selectCount(any())).thenReturn(0L);

            spy.deleteDepart(7L);

            verify(sysDepartMapper).deleteById(7L);
        }
    }

    @Nested
    @DisplayName("后代部门与用户查询")
    class Query {

        @Test
        @DisplayName("listDepartAndChildIds 含自身与全部后代")
        void departAndChild() {
            when(sysDepartMapper.selectList(any())).thenReturn(List.of(
                    depart(1L, "总部", null),
                    depart(2L, "研发中心", 1L),
                    depart(3L, "测试组", 2L),
                    depart(4L, "市场部", 1L)));

            List<Long> ids = departService.listDepartAndChildIds(2L);

            assertThat(ids).containsExactlyInAnyOrder(2L, 3L);
        }

        @Test
        @DisplayName("listUserIdsByDepartIds 空入参返回空列表（不查库）")
        void emptyDepartIds() {
            assertThat(departService.listUserIdsByDepartIds(List.of())).isEmpty();
            verify(sysUserDepartMapper, never()).selectList(any());
        }

        @Test
        @DisplayName("listUserIdsByDepartIds 按部门取用户并去重")
        void userIdsByDeparts() {
            SysUserDepart a = new SysUserDepart();
            a.setDepartId(1L);
            a.setUserId(100L);
            SysUserDepart b = new SysUserDepart();
            b.setDepartId(1L);
            b.setUserId(100L);
            SysUserDepart c = new SysUserDepart();
            c.setDepartId(2L);
            c.setUserId(200L);
            when(sysUserDepartMapper.selectList(any())).thenReturn(List.of(a, b, c));

            List<Long> userIds = departService.listUserIdsByDepartIds(List.of(1L, 2L));

            assertThat(userIds).containsExactlyInAnyOrder(100L, 200L);
        }
    }
}
