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
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.mapper.CredentialVaultMapper;
import com.helloai.core.system.port.PlatformApiKeyChange;
import com.helloai.core.system.port.PlatformApiKeyView;
import com.helloai.core.system.service.impl.CredentialVaultServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
    private CredentialCryptoService cryptoService;
    private CredentialVaultServiceImpl service;

    @BeforeEach
    void setUp() {
        vaultMapper = mock(CredentialVaultMapper.class);
        auditService = mock(CredentialAuditService.class);
        cryptoService = mock(CredentialCryptoService.class);
        service = spy(new CredentialVaultServiceImpl(auditService, cryptoService));
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
        @DisplayName("saveAgentApiKeyCredential：旧 ACTIVE 停用 + 新凭证保存（含指纹）+ BIND 审计")
        void shouldAuditOnSave() {
            when(vaultMapper.update(isNull(), any())).thenReturn(1);
            doReturn(true).when(service).save(any(CredentialVault.class));

            service.saveAgentApiKeyCredential(AGENT_ID, PROVIDER, "cipher", null, null, "r", "fp-save");

            ArgumentCaptor<CredentialVault> captor = ArgumentCaptor.forClass(CredentialVault.class);
            verify(service).save(captor.capture());
            CredentialVault saved = captor.getValue();
            assertThat(saved.getFingerprint()).isEqualTo("fp-save");
            verify(auditService).record(eq(saved.getId()), eq(CredentialOwnerType.AGENT), eq(AGENT_ID),
                    eq(PROVIDER), eq(CredentialAuditAction.BIND), eq(CredentialAuditAction.OPERATOR_ADMIN), anyString());
        }

        @Test
        @DisplayName("rotateAgentApiKey：旧→EXPIRED + 新凭证（含指纹）+ ROTATE 审计（detail 含 rotated_from_id）")
        void shouldAuditOnRotate() {
            CredentialVault oldVault = vault(9L, CredentialStatus.ACTIVE, null);
            when(vaultMapper.selectOne(any())).thenReturn(oldVault);
            when(vaultMapper.update(isNull(), any())).thenReturn(1);
            doReturn(true).when(service).save(any(CredentialVault.class));

            service.rotateAgentApiKey(AGENT_ID, PROVIDER, "cipher-new", null, "rotate-remark", "fp-rotate");

            ArgumentCaptor<CredentialVault> captor = ArgumentCaptor.forClass(CredentialVault.class);
            verify(service).save(captor.capture());
            CredentialVault saved = captor.getValue();
            assertThat(saved.getFingerprint()).isEqualTo("fp-rotate");
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

    @Nested
    @DisplayName("`M-7` saveDisabledPlatformApiKeyCredential 备位落库")
    class SaveDisabledPlatformApiKey {

        @Test
        @DisplayName("落库 status=DISABLED、owner=PLATFORM/0，保留指纹与密文，secretRef/expireTime 为空")
        void shouldPersistAsDisabled() {
            service.saveDisabledPlatformApiKeyCredential(PROVIDER, "cipher-1", "备位", "fp-1");

            ArgumentCaptor<CredentialVault> captor = ArgumentCaptor.forClass(CredentialVault.class);
            verify(vaultMapper).insert(captor.capture());
            CredentialVault saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(CredentialStatus.DISABLED);
            assertThat(saved.getOwnerType()).isEqualTo(CredentialOwnerType.PLATFORM);
            assertThat(saved.getOwnerId()).isZero();
            assertThat(saved.getProvider()).isEqualTo(PROVIDER);
            assertThat(saved.getCredentialType()).isEqualTo(CredentialType.API_KEY);
            assertThat(saved.getEncryptedValue()).isEqualTo("cipher-1");
            assertThat(saved.getFingerprint()).isEqualTo("fp-1");
            assertThat(saved.getRemark()).isEqualTo("备位");
            assertThat(saved.getSecretRef()).isNull();
            assertThat(saved.getExpireTime()).isNull();
        }

        @Test
        @DisplayName("★与「顶替式」保存的区别：备位落库不产生任何 UPDATE，原 ACTIVE 行不受影响")
        void shouldNotUpdateExistingRows() {
            service.saveDisabledPlatformApiKeyCredential(PROVIDER, "cipher-1", "备位", "fp-1");

            verify(vaultMapper, never()).update(any(), any());
            // 带类型实参：BaseMapper#updateById 有 T / Collection<T> 两个重载，裸 any() 会歧义
            verify(vaultMapper, never()).updateById(any(CredentialVault.class));
        }

        @Test
        @DisplayName("落库带 BIND 审计，detail 标明备位语义（与启用位写入可区分）")
        void shouldRecordBindAudit() {
            service.saveDisabledPlatformApiKeyCredential(PROVIDER, "cipher-1", "备位", "fp-1");

            verify(auditService).record(any(), eq(CredentialOwnerType.PLATFORM), eq(0L), eq(PROVIDER),
                    eq(CredentialAuditAction.BIND), eq(CredentialAuditAction.OPERATOR_ADMIN),
                    eq("batch add as standby (DISABLED)"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  `M-8` 多 Key 主备（列表 / 手动切换 / 停用 / 删除）
    // ══════════════════════════════════════════════════════════════════════

    /** 平台级凭证工厂（`M-8`）；密文固定为 {@code cipher-<id>}，便于按行桩解密。 */
    private CredentialVault platformVault(Long id, CredentialStatus status, String fingerprint) {
        CredentialVault v = new CredentialVault();
        v.setId(id);
        v.setOwnerType(CredentialOwnerType.PLATFORM);
        v.setOwnerId(0L);
        v.setProvider(PROVIDER);
        v.setCredentialType(CredentialType.API_KEY);
        v.setStatus(status);
        v.setFingerprint(fingerprint);
        v.setEncryptedValue("cipher-" + id);
        v.setCreateTime(OffsetDateTime.now());
        return v;
    }

    /** 统一断言：某行被降级（REVOKE 审计）。 */
    private void assertDemoted(InOrder order, Long id) {
        order.verify(auditService).record(eq(id), eq(CredentialOwnerType.PLATFORM), eq(0L), eq(PROVIDER),
                eq(CredentialAuditAction.REVOKE), anyString(), anyString());
    }

    /** 统一断言：某行被升为启用位（ACTIVATE 审计）。 */
    private void assertPromoted(InOrder order, Long id) {
        order.verify(auditService).record(eq(id), eq(CredentialOwnerType.PLATFORM), eq(0L), eq(PROVIDER),
                eq(CredentialAuditAction.ACTIVATE), anyString(), anyString());
    }

    @Nested
    @DisplayName("`M-8` listPlatformApiKeyViews 主备列表")
    class ListPlatformApiKeyViews {

        @Test
        @DisplayName("排序：启用位 → 备位 → 历史（EXPIRED）；打乱输入仍由服务排好（不依赖 SQL 方言）")
        void shouldOrderActiveStandbyThenExpired() {
            when(vaultMapper.selectList(any())).thenReturn(List.of(
                    platformVault(3L, CredentialStatus.EXPIRED, "fp-expired"),
                    platformVault(2L, CredentialStatus.DISABLED, "fp-standby"),
                    platformVault(1L, CredentialStatus.ACTIVE, "fp-active")));
            when(cryptoService.decryptFromBase64(anyString())).thenReturn("sk-abcdefgh1234");

            List<PlatformApiKeyView> views = service.listPlatformApiKeyViews(PROVIDER);

            assertThat(views).extracting(PlatformApiKeyView::id).containsExactly(1L, 2L, 3L);
            assertThat(views).extracting(PlatformApiKeyView::status).containsExactly(
                    CredentialStatus.ACTIVE, CredentialStatus.DISABLED, CredentialStatus.EXPIRED);
        }

        @Test
        @DisplayName("逐行脱敏：明文经 SecretMask（前 4 + … + 后 4），指纹原样透出")
        void shouldMaskEachRow() {
            when(vaultMapper.selectList(any())).thenReturn(List.of(
                    platformVault(1L, CredentialStatus.ACTIVE, "fp-active")));
            when(cryptoService.decryptFromBase64("cipher-1")).thenReturn("sk-abcdefgh1234");

            List<PlatformApiKeyView> views = service.listPlatformApiKeyViews(PROVIDER);

            assertThat(views).hasSize(1);
            assertThat(views.get(0).masked()).isEqualTo("sk-a…1234");
            assertThat(views.get(0).fingerprint()).isEqualTo("fp-active");
        }

        @Test
        @DisplayName("★best-effort：单行解密失败只该行 masked=null，不阻断列表其余行")
        void shouldNotBreakListWhenOneRowFailsToDecrypt() {
            when(vaultMapper.selectList(any())).thenReturn(List.of(
                    platformVault(1L, CredentialStatus.ACTIVE, "fp-a"),
                    platformVault(9L, CredentialStatus.DISABLED, "fp-bad")));
            when(cryptoService.decryptFromBase64(anyString())).thenAnswer(inv -> {
                String cipher = inv.getArgument(0);
                if ("cipher-9".equals(cipher)) {
                    throw new BizException("凭证解密失败: 主密钥已轮换");
                }
                return "sk-abcdefgh1234";
            });

            List<PlatformApiKeyView> views = service.listPlatformApiKeyViews(PROVIDER);

            assertThat(views).hasSize(2);
            assertThat(views.get(0).masked()).isEqualTo("sk-a…1234");
            assertThat(views.get(1).id()).isEqualTo(9L);
            assertThat(views.get(1).masked()).isNull();
        }

        @Test
        @DisplayName("provider 为空 ⇒ 返回空列表，不触库")
        void shouldReturnEmptyForBlankProvider() {
            assertThat(service.listPlatformApiKeyViews("  ")).isEmpty();
            verify(vaultMapper, never()).selectList(any());
        }
    }

    @Nested
    @DisplayName("`M-8` activatePlatformApiKeyCredential 手动切换启用位")
    class Activate {

        @Test
        @DisplayName("★先降后升：原启用位先降级、目标备位随后升位（部分唯一索引只允许一条 ACTIVE）")
        void shouldDemoteBeforePromote() {
            when(vaultMapper.selectById(2L)).thenReturn(platformVault(2L, CredentialStatus.DISABLED, "fp-standby"));
            when(vaultMapper.selectOne(any())).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-active"));
            when(vaultMapper.update(isNull(), any())).thenReturn(1);

            PlatformApiKeyChange change = service.activatePlatformApiKeyCredential(2L, PROVIDER, "admin");

            assertThat(change.credentialId()).isEqualTo(2L);
            assertThat(change.fingerprint()).isEqualTo("fp-standby");
            assertThat(change.previousActiveId()).isEqualTo(1L);
            assertThat(change.activeId()).isEqualTo(2L);

            InOrder order = inOrder(auditService);
            assertDemoted(order, 1L);
            assertPromoted(order, 2L);
        }

        @Test
        @DisplayName("此前无启用位 ⇒ previousActiveId=null，仅升级一次（无降级步骤）")
        void shouldActivateWithoutPreviousActive() {
            when(vaultMapper.selectById(2L)).thenReturn(platformVault(2L, CredentialStatus.DISABLED, "fp-standby"));
            when(vaultMapper.selectOne(any())).thenReturn(null);
            when(vaultMapper.update(isNull(), any())).thenReturn(1);

            PlatformApiKeyChange change = service.activatePlatformApiKeyCredential(2L, PROVIDER, "admin");

            assertThat(change.previousActiveId()).isNull();
            assertThat(change.activeId()).isEqualTo(2L);
            verify(vaultMapper, times(1)).update(isNull(), any());
            verify(auditService, never()).record(any(), any(), any(), anyString(),
                    eq(CredentialAuditAction.REVOKE), anyString(), anyString());
        }

        @Test
        @DisplayName("目标已是启用位 ⇒ 拒绝（不静默成功，避免调用方误以为发生了切换）")
        void shouldRejectAlreadyActive() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-active"));

            assertThatThrownBy(() -> service.activatePlatformApiKeyCredential(1L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已是启用位");

            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("EXPIRED 为轮换淘汰终态 ⇒ 不可启用")
        void shouldRejectExpiredTarget() {
            when(vaultMapper.selectById(3L)).thenReturn(platformVault(3L, CredentialStatus.EXPIRED, "fp-e"));

            assertThatThrownBy(() -> service.activatePlatformApiKeyCredential(3L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可启用");

            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("★凭证不属于该 provider ⇒ 拒绝且不触库（防跨 Provider 误切）")
        void shouldRejectProviderMismatch() {
            when(vaultMapper.selectById(2L)).thenReturn(platformVault(2L, CredentialStatus.DISABLED, "fp-standby"));

            assertThatThrownBy(() -> service.activatePlatformApiKeyCredential(2L, "openai", "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不匹配");

            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("Agent 级凭证不得走平台级主备切换 ⇒ 拒绝")
        void shouldRejectNonPlatformOwner() {
            when(vaultMapper.selectById(CREDENTIAL_ID))
                    .thenReturn(vault(CREDENTIAL_ID, CredentialStatus.DISABLED, null));

            assertThatThrownBy(() -> service.activatePlatformApiKeyCredential(CREDENTIAL_ID, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("仅支持平台级");
        }

        @Test
        @DisplayName("凭证不存在 ⇒ 拒绝")
        void shouldRejectMissingCredential() {
            when(vaultMapper.selectById(99L)).thenReturn(null);

            assertThatThrownBy(() -> service.activatePlatformApiKeyCredential(99L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("升位 CAS 冲突（并发已改状态）⇒ 抛错交由事务回滚（降级步骤一并撤销）")
        void shouldRejectCasConflictOnPromote() {
            when(vaultMapper.selectById(2L)).thenReturn(platformVault(2L, CredentialStatus.DISABLED, "fp-standby"));
            when(vaultMapper.selectOne(any())).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-active"));
            when(vaultMapper.update(isNull(), any())).thenReturn(1, 0);

            assertThatThrownBy(() -> service.activatePlatformApiKeyCredential(2L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("状态冲突");
        }
    }

    @Nested
    @DisplayName("`M-8` deactivatePlatformApiKeyCredential 停用")
    class Deactivate {

        @Test
        @DisplayName("停用启用位且有备位 ⇒ 备位顶上（先降后升）")
        void shouldPromoteSuccessorWhenDeactivatingActive() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            when(vaultMapper.selectList(any())).thenReturn(List.of(
                    platformVault(2L, CredentialStatus.DISABLED, "fp-s")));
            when(vaultMapper.update(isNull(), any())).thenReturn(1);

            PlatformApiKeyChange change = service.deactivatePlatformApiKeyCredential(1L, PROVIDER, "admin");

            assertThat(change.previousActiveId()).isEqualTo(1L);
            assertThat(change.activeId()).isEqualTo(2L);

            InOrder order = inOrder(auditService);
            assertDemoted(order, 1L);
            assertPromoted(order, 2L);
        }

        @Test
        @DisplayName("★停用唯一的启用 Key（无备位可顶）⇒ fail-close 拒绝，不触任何 UPDATE")
        void shouldRejectWhenNoSuccessor() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            when(vaultMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.deactivatePlatformApiKeyCredential(1L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("唯一的启用 Key");

            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("停用已是备位的行 ⇒ 幂等空操作（不触 UPDATE），启用位不变")
        void shouldBeIdempotentWhenTargetIsStandby() {
            when(vaultMapper.selectById(2L)).thenReturn(platformVault(2L, CredentialStatus.DISABLED, "fp-s"));
            when(vaultMapper.selectOne(any())).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));

            PlatformApiKeyChange change = service.deactivatePlatformApiKeyCredential(2L, PROVIDER, "admin");

            assertThat(change.previousActiveId()).isEqualTo(1L);
            assertThat(change.activeId()).isEqualTo(1L);
            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("EXPIRED ⇒ 拒绝（轮换终态不可逆）")
        void shouldRejectExpired() {
            when(vaultMapper.selectById(3L)).thenReturn(platformVault(3L, CredentialStatus.EXPIRED, "fp-e"));

            assertThatThrownBy(() -> service.deactivatePlatformApiKeyCredential(3L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不可停用");
        }
    }

    @Nested
    @DisplayName("`M-8` deletePlatformApiKeyCredential 删除（软删）")
    class DeleteKey {

        @Test
        @DisplayName("删除备位 ⇒ 走 IService#removeById（带 @TableLogic ⇒ 软删），不动启用位")
        void shouldSoftDeleteStandby() {
            when(vaultMapper.selectById(2L)).thenReturn(platformVault(2L, CredentialStatus.DISABLED, "fp-s"));
            when(vaultMapper.selectOne(any())).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            doReturn(true).when(service).removeById(any(Serializable.class));

            PlatformApiKeyChange change = service.deletePlatformApiKeyCredential(2L, PROVIDER, "admin");

            assertThat(change.credentialId()).isEqualTo(2L);
            assertThat(change.previousActiveId()).isEqualTo(1L);
            assertThat(change.activeId()).isEqualTo(1L);
            verify(service).removeById(2L);
            verify(vaultMapper, never()).update(any(), any());
            verify(auditService).record(eq(2L), eq(CredentialOwnerType.PLATFORM), eq(0L), eq(PROVIDER),
                    eq(CredentialAuditAction.DELETE), eq("admin"), anyString());
        }

        @Test
        @DisplayName("删除启用位且有备位 ⇒ 先顶替再删；审计文案记录「删除时的状态」")
        void shouldPromoteThenDeleteActive() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            when(vaultMapper.selectList(any())).thenReturn(List.of(
                    platformVault(2L, CredentialStatus.DISABLED, "fp-s")));
            when(vaultMapper.update(isNull(), any())).thenReturn(1);
            doReturn(true).when(service).removeById(any(Serializable.class));

            PlatformApiKeyChange change = service.deletePlatformApiKeyCredential(1L, PROVIDER, "admin");

            assertThat(change.previousActiveId()).isEqualTo(1L);
            assertThat(change.activeId()).isEqualTo(2L);
            verify(service).removeById(1L);
            // 先降后升发生在删除之前 ⇒ 审计文案必须描述「删除时它是 ACTIVE」，不能是已被改写的 DISABLED
            verify(auditService).record(eq(1L), eq(CredentialOwnerType.PLATFORM), eq(0L), eq(PROVIDER),
                    eq(CredentialAuditAction.DELETE), eq("admin"),
                    org.mockito.ArgumentMatchers.contains("status at delete=ACTIVE"));
        }

        @Test
        @DisplayName("★删除唯一的启用 Key（无备位可顶）⇒ 拒绝，且不删任何行")
        void shouldRejectDeletingLastActiveKey() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            when(vaultMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.deletePlatformApiKeyCredential(1L, PROVIDER, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("唯一的启用 Key");

            verify(service, never()).removeById(any(Serializable.class));
            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("删除 EXPIRED 历史行 ⇒ 允许（清理轮换淘汰记录），启用位不变")
        void shouldAllowDeletingExpiredHistory() {
            when(vaultMapper.selectById(3L)).thenReturn(platformVault(3L, CredentialStatus.EXPIRED, "fp-e"));
            when(vaultMapper.selectOne(any())).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            doReturn(true).when(service).removeById(any(Serializable.class));

            PlatformApiKeyChange change = service.deletePlatformApiKeyCredential(3L, PROVIDER, "admin");

            assertThat(change.activeId()).isEqualTo(1L);
            verify(service).removeById(3L);
        }
    }

    @Nested
    @DisplayName("`M-8` revokeCredential 的平台级守卫（防旧端点绕过）")
    class RevokePlatformGuard {

        @Test
        @DisplayName("平台级 ACTIVE 且有备位 ⇒ 顶替后降级（旧端点同样受「启用位不可缺失」约束）")
        void shouldPromoteWhenRevokingPlatformActive() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            when(vaultMapper.selectList(any())).thenReturn(List.of(
                    platformVault(2L, CredentialStatus.DISABLED, "fp-s")));
            when(vaultMapper.update(isNull(), any())).thenReturn(1);

            CredentialVault result = service.revokeCredential(1L, "admin");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.DISABLED);
            InOrder order = inOrder(auditService);
            assertDemoted(order, 1L);
            assertPromoted(order, 2L);
        }

        @Test
        @DisplayName("★平台级 ACTIVE 且无备位 ⇒ 拒绝（否则整个 Provider 静默变「未配置」）")
        void shouldRejectWhenNoSuccessorOnPlatformActive() {
            when(vaultMapper.selectById(1L)).thenReturn(platformVault(1L, CredentialStatus.ACTIVE, "fp-a"));
            when(vaultMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.revokeCredential(1L, "admin"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("唯一的启用 Key");

            verify(vaultMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("Agent 级 ACTIVE 仍走原路径：不找继任者、不加守卫（N-004 语义不变）")
        void shouldKeepAgentPathUnchanged() {
            when(vaultMapper.selectById(CREDENTIAL_ID))
                    .thenReturn(vault(CREDENTIAL_ID, CredentialStatus.ACTIVE, null));
            when(vaultMapper.update(isNull(), any())).thenReturn(1);

            CredentialVault result = service.revokeCredential(CREDENTIAL_ID, "admin");

            assertThat(result.getStatus()).isEqualTo(CredentialStatus.DISABLED);
            // Agent 级不触发「找继任备位」，故不应有 selectList 调用
            verify(vaultMapper, never()).selectList(any());
        }
    }
}
