package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysPermissionDataRule;
import com.helloai.core.system.mapper.SysPermissionDataRuleMapper;
import com.helloai.core.system.mapper.SysPermissionMapper;
import com.helloai.core.system.service.impl.SysPermissionDataRuleServiceImpl;
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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysPermissionDataRuleServiceImpl 单测（数据权限，受控枚举，BASE-3.3）。
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>saveRule：规则类型白名单校验 / CUSTOM 必填部门范围 / 权限码存在性</li>
 *   <li>saveRule：同步 rule_flag（ALL → 0，其余 → 1）</li>
 *   <li>resolveVisibleUserIds：未配置 / ALL → null（不限制）</li>
 *   <li>resolveVisibleUserIds：DEPT → 本部门用户；CUSTOM → 指定部门用户</li>
 *   <li>resolveVisibleUserIds：非法规则类型 → fail-close 空集合</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysPermissionDataRuleServiceImpl 数据规则服务")
class SysPermissionDataRuleServiceImplTest {

    @Mock
    private SysPermissionDataRuleMapper dataRuleMapper;
    @Mock
    private SysPermissionMapper sysPermissionMapper;
    @Mock
    private SysDepartService sysDepartService;

    private SysPermissionDataRuleServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SysPermissionDataRuleServiceImpl(sysPermissionMapper, sysDepartService);
        injectBaseMapper(service, dataRuleMapper);
    }

    private static void injectBaseMapper(Object target, Object mapper) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(target, mapper);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new IllegalStateException("baseMapper 字段未找到");
    }

    private SysPermission permission(Long id, String code, Integer ruleFlag) {
        SysPermission p = new SysPermission();
        p.setId(id);
        p.setCode(code);
        p.setRuleFlag(ruleFlag);
        return p;
    }

    private SysPermissionDataRule rule(Long permissionId, String type, String value) {
        SysPermissionDataRule r = new SysPermissionDataRule();
        r.setPermissionId(permissionId);
        r.setRuleType(type);
        r.setRuleValue(value);
        return r;
    }

    @Nested
    @DisplayName("规则保存")
    class Save {

        @Test
        @DisplayName("非法规则类型 → BizException")
        void illegalType_shouldThrow() {
            when(sysPermissionMapper.selectById(1L)).thenReturn(permission(1L, "user:view", 0));

            BizException ex = assertThrows(BizException.class,
                    () -> service.saveRule(1L, "SQL_INJECT", null));
            assertThat(ex.getMessage()).contains("ALL / DEPT");
            verify(dataRuleMapper, never()).insert(any(SysPermissionDataRule.class));
        }

        @Test
        @DisplayName("CUSTOM 未给部门范围 → BizException")
        void customWithoutValue_shouldThrow() {
            when(sysPermissionMapper.selectById(1L)).thenReturn(permission(1L, "user:view", 0));

            BizException ex = assertThrows(BizException.class,
                    () -> service.saveRule(1L, "CUSTOM", "  "));
            assertThat(ex.getMessage()).contains("CUSTOM");
        }

        @Test
        @DisplayName("权限码不存在 → BizException")
        void permissionMissing_shouldThrow() {
            when(sysPermissionMapper.selectById(404L)).thenReturn(null);

            BizException ex = assertThrows(BizException.class,
                    () -> service.saveRule(404L, "DEPT", null));
            assertThat(ex.getMessage()).contains("不存在");
        }

        @Test
        @DisplayName("ALL → rule_flag=0（等同未配置）")
        void allRule_shouldClearFlag() {
            when(sysPermissionMapper.selectById(1L)).thenReturn(permission(1L, "user:view", 1));

            service.saveRule(1L, "ALL", null);

            ArgumentCaptor<SysPermission> captor = ArgumentCaptor.forClass(SysPermission.class);
            verify(sysPermissionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getRuleFlag()).isZero();
        }

        @Test
        @DisplayName("DEPT → rule_flag=1 且规则落库")
        void deptRule_shouldSetFlag() {
            when(sysPermissionMapper.selectById(1L)).thenReturn(permission(1L, "user:view", 0));

            service.saveRule(1L, "DEPT", null);

            ArgumentCaptor<SysPermission> captor = ArgumentCaptor.forClass(SysPermission.class);
            verify(sysPermissionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getRuleFlag()).isEqualTo(1);
            verify(dataRuleMapper).insert(any(SysPermissionDataRule.class));
        }
    }

    @Nested
    @DisplayName("可见范围解析")
    class Resolve {

        @Test
        @DisplayName("权限码不存在 → null（不限制）")
        void permissionMissing_shouldReturnNull() {
            when(sysPermissionMapper.selectList(any())).thenReturn(List.of());

            assertThat(service.resolveVisibleUserIds(100L, "user:view")).isNull();
        }

        @Test
        @DisplayName("rule_flag=0（未配置）→ null")
        void ruleFlagOff_shouldReturnNull() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 0)));

            assertThat(service.resolveVisibleUserIds(100L, "user:view")).isNull();
        }

        @Test
        @DisplayName("规则为 ALL → null")
        void allRule_shouldReturnNull() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 1)));
            when(dataRuleMapper.selectList(any())).thenReturn(List.of(rule(1L, "ALL", null)));

            assertThat(service.resolveVisibleUserIds(100L, "user:view")).isNull();
        }

        @Test
        @DisplayName("DEPT → 仅本部门用户")
        void deptRule_shouldScopeOwnDepart() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 1)));
            when(dataRuleMapper.selectList(any())).thenReturn(List.of(rule(1L, "DEPT", null)));
            when(sysDepartService.listDepartIdsByUser(100L)).thenReturn(List.of(10L));
            when(sysDepartService.listUserIdsByDepartIds(any())).thenReturn(List.of(100L, 101L));

            Set<Long> visible = service.resolveVisibleUserIds(100L, "user:view");

            assertThat(visible).containsExactlyInAnyOrder(100L, 101L);
        }

        @Test
        @DisplayName("CUSTOM → 指定部门用户")
        void customRule_shouldScopeGivenDeparts() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 1)));
            when(dataRuleMapper.selectList(any())).thenReturn(List.of(rule(1L, "CUSTOM", "10,11")));
            when(sysDepartService.listUserIdsByDepartIds(any())).thenReturn(List.of(200L));

            Set<Long> visible = service.resolveVisibleUserIds(100L, "user:view");

            assertThat(visible).containsExactly(200L);
            verify(sysDepartService).listUserIdsByDepartIds(
                    org.mockito.ArgumentMatchers.argThat(ids -> ids.containsAll(List.of(10L, 11L))));
        }

        @Test
        @DisplayName("DEPT_AND_CHILD → 本部门及后代用户")
        void deptAndChild_shouldExpand() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 1)));
            when(dataRuleMapper.selectList(any())).thenReturn(List.of(rule(1L, "DEPT_AND_CHILD", null)));
            when(sysDepartService.listDepartIdsByUser(100L)).thenReturn(List.of(10L));
            when(sysDepartService.listDepartAndChildIds(10L)).thenReturn(List.of(10L, 11L, 12L));
            when(sysDepartService.listUserIdsByDepartIds(any())).thenReturn(List.of(300L));

            Set<Long> visible = service.resolveVisibleUserIds(100L, "user:view");

            assertThat(visible).containsExactly(300L);
        }

        @Test
        @DisplayName("非法规则类型（脏数据）→ fail-close 空集合")
        void illegalRuleType_shouldFailClosed() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 1)));
            when(dataRuleMapper.selectList(any())).thenReturn(List.of(rule(1L, "RAW_SQL", null)));

            Set<Long> visible = service.resolveVisibleUserIds(100L, "user:view");

            assertThat(visible).isEmpty();
            verify(sysDepartService, never()).listUserIdsByDepartIds(any());
        }

        @Test
        @DisplayName("用户无部门（DEPT 规则）→ 空集合")
        void noDepart_shouldReturnEmpty() {
            when(sysPermissionMapper.selectList(any()))
                    .thenReturn(List.of(permission(1L, "user:view", 1)));
            when(dataRuleMapper.selectList(any())).thenReturn(List.of(rule(1L, "DEPT", null)));
            when(sysDepartService.listDepartIdsByUser(anyLong())).thenReturn(List.of());

            assertThat(service.resolveVisibleUserIds(100L, "user:view")).isEmpty();
        }
    }
}
