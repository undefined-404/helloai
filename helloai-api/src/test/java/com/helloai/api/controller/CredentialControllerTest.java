package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.credential.AuditLogResponse;
import com.helloai.api.dto.credential.RotateAgentApiKeyRequest;
import com.helloai.api.interceptor.AuthInterceptor;
import com.helloai.common.base.R;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.constant.CredentialStatus;
import com.helloai.common.constant.CredentialType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialController} B2（N-004 收口）单元测试：
 * rotate / revoke / 审计查询端点转发与返回封装。
 *
 * <p>核心断言点：
 * <ul>
 *   <li>3 端点参数原样透传 core/binding 服务（agentId / id / 过滤条件 / 分页）；</li>
 *   <li>返回 {@code R} 封装 code=200，DTO 字段映射正确（审计不含密文）；</li>
 *   <li>控制器无编排：每次调用只触达对应服务一个方法（{@code verifyNoMoreInteractions}）；</li>
 *   <li>requireAdmin：非 admin 身份 403（fail-close）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialController 凭证管理端点（B2）")
class CredentialControllerTest {

    @Mock
    private CredentialVaultBindingService credentialVaultBindingService;

    @Mock
    private CredentialVaultService credentialVaultService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private CredentialController controller;

    private static final Long AGENT_ID = 1L;
    private static final Long CREDENTIAL_ID = 7L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-05T12:00:00+08:00");

    private void asAdmin() {
        when(request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY)).thenReturn("admin");
    }

    private CredentialVault vault(Long id, CredentialStatus status) {
        CredentialVault v = new CredentialVault();
        v.setId(id);
        v.setOwnerType(CredentialOwnerType.AGENT);
        v.setOwnerId(AGENT_ID);
        v.setProvider("deepseek");
        v.setCredentialType(CredentialType.API_KEY);
        v.setStatus(status);
        v.setEncryptedValue("cipher-x");
        v.setCreateTime(NOW);
        v.setUpdateTime(NOW);
        v.setRemark("rotate-remark");
        return v;
    }

    private CredentialAuditLog auditLog(long id) {
        CredentialAuditLog log = new CredentialAuditLog();
        log.setId(id);
        log.setCredentialId(CREDENTIAL_ID);
        log.setOwnerType(CredentialOwnerType.AGENT);
        log.setOwnerId(AGENT_ID);
        log.setProvider("deepseek");
        log.setAction("rotate");
        log.setOperator("admin");
        log.setDetail("rotated_from_id=6");
        log.setCreateTime(NOW);
        return log;
    }

    @Test
    @DisplayName("rotateByAgentId：agentId/provider/apiKey/remark 透传 binding 服务，DTO 脱敏映射")
    void shouldRotateByAgentId() {
        asAdmin();
        RotateAgentApiKeyRequest req = new RotateAgentApiKeyRequest();
        req.setProvider("deepseek");
        req.setApiKey("sk-new");
        req.setRemark("rotate-remark");
        when(credentialVaultBindingService.rotateAgentApiKey(
                eq(AGENT_ID), eq("deepseek"), eq("sk-new"), eq("rotate-remark")))
                .thenReturn(vault(CREDENTIAL_ID, CredentialStatus.ACTIVE));

        R<com.helloai.api.dto.credential.CredentialInfoResponse> resp =
                controller.rotateByAgentId(AGENT_ID, req);

        assertThat(resp.getCode()).isEqualTo(200);
        com.helloai.api.dto.credential.CredentialInfoResponse info = resp.getData();
        assertThat(info.getId()).isEqualTo(CREDENTIAL_ID);
        assertThat(info.getOwnerType()).isEqualTo("AGENT");
        assertThat(info.getStatus()).isEqualTo("ACTIVE");
        assertThat(info.getRemark()).isEqualTo("rotate-remark");
        // 响应不含明文
        assertThat(info.isHasEncryptedValue()).isTrue();
        verifyNoMoreInteractions(credentialVaultBindingService);
    }

    @Test
    @DisplayName("revoke：id 透传 service（operator=admin），返回 R.ok（无编排）")
    void shouldRevoke() {
        asAdmin();

        R<Void> resp = controller.revoke(CREDENTIAL_ID);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isNull();
        verify(credentialVaultService).revokeCredential(CREDENTIAL_ID, "admin");
        verifyNoMoreInteractions(credentialVaultService);
    }

    @Test
    @DisplayName("listAudits：过滤条件 + 分页透传，AuditLogResponse 字段映射正确且不含密文")
    void shouldListAudits() {
        asAdmin();
        Page<CredentialAuditLog> page = new Page<>(2, 10, 1);
        page.setRecords(List.of(auditLog(11L)));
        when(credentialVaultService.listAudits(CREDENTIAL_ID, CredentialOwnerType.AGENT, AGENT_ID, 2, 10))
                .thenReturn(page);

        R<PageResult<AuditLogResponse>> resp =
                controller.listAudits(CREDENTIAL_ID, CredentialOwnerType.AGENT, AGENT_ID, 2, 10);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getTotal()).isEqualTo(1);
        assertThat(resp.getData().getCurrent()).isEqualTo(2);
        AuditLogResponse log = resp.getData().getList().get(0);
        assertThat(log.getId()).isEqualTo(11L);
        assertThat(log.getCredentialId()).isEqualTo(CREDENTIAL_ID);
        assertThat(log.getOwnerType()).isEqualTo("AGENT");
        assertThat(log.getAction()).isEqualTo("rotate");
        assertThat(log.getOperator()).isEqualTo("admin");
        assertThat(log.getDetail()).isEqualTo("rotated_from_id=6");
        assertThat(log.getCreateTime()).isEqualTo(NOW);
        verifyNoMoreInteractions(credentialVaultService);
    }

    @Test
    @DisplayName("requireAdmin：非 admin 身份抛 403（fail-close），不触达服务")
    void shouldRejectNonAdmin() {
        when(request.getAttribute(AuthInterceptor.AUTH_TYPE_KEY)).thenReturn("agent");

        assertThatThrownBy(() -> controller.revoke(CREDENTIAL_ID))
                .isInstanceOf(com.helloai.common.base.BizException.class)
                .satisfies(e -> assertThat(((com.helloai.common.base.BizException) e).getCode()).isEqualTo(403));

        verifyNoMoreInteractions(credentialVaultService);
    }

    @Test
    @DisplayName("rotate 审计查询空 owner 条件：null 透传（不误传空过滤）")
    void shouldListAuditsWithNullOwner() {
        asAdmin();
        when(credentialVaultService.listAudits(null, null, null, 1, 20))
                .thenReturn(new Page<>(1, 20));

        R<PageResult<AuditLogResponse>> resp = controller.listAudits(null, null, null, 1, 20);

        assertThat(resp.getCode()).isEqualTo(200);
        verify(credentialVaultService).listAudits(null, null, null, 1, 20);
        verifyNoMoreInteractions(credentialVaultService);
    }
}
