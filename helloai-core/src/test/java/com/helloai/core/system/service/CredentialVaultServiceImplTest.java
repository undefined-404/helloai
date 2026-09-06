package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.CredentialAuditAction;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import com.helloai.core.system.service.impl.CredentialVaultServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialVaultService} B2（N-004 收口）单元测试：
 * 人工停用（revoke CAS）/ 过期扫描（expireOverdue）/ 审计落点（bind/rotate/revoke/expire）。
 *
 * <p>spy + mock baseMapper 隔离 MyBatis-Plus / 数据库；TableInfo 预热规避
 * MP 3.5.9 lambda 缓存坑（参照 {@code AgentCommandOutboxServiceImplTest}）。</p>
 */
@DisplayName("CredentialVaultService 生命周期与审计（B2）")
class CredentialVaultServiceImplTest {

    private static final Long CREDENTIAL_ID = 5L;
    private static final Long AGENT_ID = 1L;
    private static final String PROVIDER = "deepseek";

    @BeforeAll
    static void initTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CredentialVault.class);
        TableInfoHelper.initTableInfo(assistant, CredentialAuditLog.class);
    }

    private CredentialVaultMapper vaultMapper;
    private CredentialAuditService auditService;
    private CredentialVaultServiceImpl service;

    @BeforeEach
    void setUp() {
        vaultMapper = mock(CredentialVaultMapper.class);
        auditService = mock(CredentialAuditService.class);
        service = spy(new CredentialVaultServiceImpl(auditService));
        ReflectionTestUtils.setField(service, "baseMapper", vaultMapper);
    }

    private CredentialVault vault(Long id, CredentialStatus status, OffsetDateTime expiresAt) {
        CredentialVault v = new CredentialVault();
        v.setId(id);
        v.setOwnerType(CredentialOwnerType.AGENT);
        v.setOwnerId(AGENT_ID);
        v.setProvider(PROVIDER);
        v.setCredentialType(CredentialType.API_KEY);
        v.setStatus(status);
        v.setExpireTime(expiresAt);
        return v;
    }

    @Nested
    @DisplayName("revokeCredential 人工停用")
    class Revoke {

        @Test
        @DisplayName("ACTIVE 行 → DISABLED + REVOKE 审计（CAS 命中）")
        void shouldRevokeActiveToDisabled() {
            when(vaultMapper.selectById(CREDENTIAL_ID)).thenReturn(vault(CREDENTIAL_ID, CredentialStatus.ACTIVE, null));
            when(vaultMapper.update(isNull(), any())).thenReturn(1);

            CredentialVault result = service.revokeCredential(CREDENTIAL_ID, "admin");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.DISABLED);
            verify(auditService).record(eq(CREDENTIAL_ID), eq(CredentialOwnerType.AGENT), eq(AGENT_ID),
                    eq(PROVIDER), eq(CredentialAuditAction.REVOKE), eq("admin"), anyString());
        }

        @Test
        @DisplayName("EXPIRED 行不可逆 → BizException，不落审计不触 UPDATE")
        void shouldRejectExpired() {
            when(vaultMapper.selectById(CREDENTIAL_ID)).thenReturn(vault(CREDENTIAL_ID, CredentialStatus.EXPIRED, null));

            assertThatThrownBy(() -> service.revokeCredential(CREDENTIAL_ID, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可逆");

            verify(vaultMapper, never()).update(isNull(), any());
            verify(auditService, never()).record(any(), any(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("凭证不存在 → BizException")
        void shouldRejectMissing() {
            when(vaultMapper.selectById(CREDENTIAL_ID)).thenReturn(null);

            assertThatThrownBy(() -> service.revokeCredential(CREDENTIAL_ID, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("CAS 冲突（并发已改状态）→ BizException，不落审计")
        void shouldRejectCasConflict() {
            when(vaultMapper.selectById(CREDENTIAL_ID)).thenReturn(vault(CREDENTIAL_ID, CredentialStatus.ACTIVE, null));
            when(vaultMapper.update(isNull(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.revokeCredential(CREDENTIAL_ID, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("状态冲突");

            verify(auditService, never()).record(any(), any(), any(), anyString(), anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("expireOverdue 过期扫描")
    class ExpireOverdue {

        @Test
        @DisplayName("过期 ACTIVE 行 → EXPIRED + EXPIRE 审计（operator=system），成功行计数")
        void shouldExpireOverdueRows() {
            OffsetDateTime past = OffsetDateTime.now().minusDays(1);
            List<CredentialVault> overdue = List.of(
                    vault(1L, CredentialStatus.ACTIVE, past),
                    vault(2L, CredentialStatus.ACTIVE, past));
            when(vaultMapper.selectList(any())).thenReturn(overdue);
            // 顺序桩：第 1 行 CAS 命中，第 2 行并发已失效
            when(vaultMapper.update(isNull(), any())).thenReturn(1, 0);

            int count = service.expireOverdue(100);

            assertThat(count).isEqualTo(1);
            ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> opCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditService).record(idCaptor.capture(), eq(CredentialOwnerType.AGENT), any(),
                    anyString(), actionCaptor.capture(), opCaptor.capture(), anyString());
            assertThat(idCaptor.getValue()).isEqualTo(1L);
            assertThat(actionCaptor.getValue()).isEqualTo(CredentialAuditAction.EXPIRE);
            assertThat(opCaptor.getValue()).isEqualTo(CredentialAuditAction.OPERATOR_SYSTEM);
        }

        @Test
        @DisplayName("无过期行 → 返回 0，不触审计")
        void shouldNoopWhenNoneOverdue() {
            when(vaultMapper.selectList(any())).thenReturn(List.of());

            int count = service.expireOverdue(100);

            assertThat(count).isZero();
            verify(auditService, never()).record(any(), any(), any(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("batchLimit ≤ 0 → 返回 0，不触库")
        void shouldNoopWhenBatchLimitInvalid() {
            int count = service.expireOverdue(0);

            assertThat(count).isZero();
            verify(vaultMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("审计落点（bind / rotate）")
    class AuditLanding {

        @Test
        @DisplayName("saveAgentApiKeyCredential：旧 ACTIVE 停用 + 新凭证保存 + BIND 审计")
        void shouldAuditOnSave() {
            when(vaultMapper.update(isNull(), any())).thenReturn(1);
            doReturn(true).when(service).save(any(CredentialVault.class));

            service.saveAgentApiKeyCredential(AGENT_ID, PROVIDER, "cipher", null, null, "r");

            ArgumentCaptor<CredentialVault> captor = ArgumentCaptor.forClass(CredentialVault.class);
            verify(service).save(captor.capture());
            CredentialVault saved = captor.getValue();
            verify(auditService).record(eq(saved.getId()), eq(CredentialOwnerType.AGENT), eq(AGENT_ID),
                    eq(PROVIDER), eq(CredentialAuditAction.BIND), eq(CredentialAuditAction.OPERATOR_ADMIN), anyString());
        }

        @Test
        @DisplayName("rotateAgentApiKey：旧→EXPIRED + 新凭证 + ROTATE 审计（detail 含 rotated_from_id）")
        void shouldAuditOnRotate() {
            CredentialVault oldVault = vault(9L, CredentialStatus.ACTIVE, null);
            when(vaultMapper.selectOne(any())).thenReturn(oldVault);
            when(vaultMapper.update(isNull(), any())).thenReturn(1);
            doReturn(true).when(service).save(any(CredentialVault.class));

            service.rotateAgentApiKey(AGENT_ID, PROVIDER, "cipher-new", null, "rotate-remark");

            ArgumentCaptor<CredentialVault> captor = ArgumentCaptor.forClass(CredentialVault.class);
            verify(service).save(captor.capture());
            CredentialVault saved = captor.getValue();
            verify(auditService).record(eq(saved.getId()), eq(CredentialOwnerType.AGENT), eq(AGENT_ID),
                    eq(PROVIDER), eq(CredentialAuditAction.ROTATE), eq(CredentialAuditAction.OPERATOR_ADMIN),
                    org.mockito.ArgumentMatchers.contains("rotated_from_id=9"));
        }
    }

    @Nested
    @DisplayName("listAudits 转发")
    class ListAudits {

        @Test
        @DisplayName("条件与分页透传给 auditService，结果原样返回")
        void shouldDelegateToAuditService() {
            Page<CredentialAuditLog> page = new Page<>(1, 20);
            when(auditService.listAudits(CREDENTIAL_ID, CredentialOwnerType.AGENT, AGENT_ID, 1, 20))
                    .thenReturn(page);

            IPage<CredentialAuditLog> result =
                    service.listAudits(CREDENTIAL_ID, CredentialOwnerType.AGENT, AGENT_ID, 1, 20);

            assertThat(result).isSameAs(page);
        }
    }
}
