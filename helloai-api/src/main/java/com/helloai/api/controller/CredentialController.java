package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.credential.AuditLogResponse;
import com.helloai.api.dto.credential.BindAgentApiKeyRequest;
import com.helloai.api.dto.credential.CredentialInfoResponse;
import com.helloai.api.dto.credential.RotateAgentApiKeyRequest;
import com.helloai.common.base.R;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.core.system.entity.CredentialAuditLog;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 凭证保险库管理端点（Phase 2 B2，N-004 收口后覆盖完整管理面）。
 *
 * <p>全部 requireAdmin：bind / list / rotate / revoke / 审计查询。
 * 明文 API Key 仅入参（bind/rotate），响应一律脱敏（{@link CredentialInfoResponse}）。
 * 不暴露明文查看端点（D-B2-5：最小暴露，明文仅在执行链内部解析）。</p>
 */
@RestController
@RequestMapping("/api/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialVaultBindingService credentialVaultBindingService;
    private final CredentialVaultService credentialVaultService;
    private final HttpServletRequest request;

    @PostMapping("/bindApiKeyByAgentId/{agentId}")
    public R<CredentialInfoResponse> bindApiKeyByAgentId(@PathVariable("agentId") Long agentId,
                                                     @RequestBody BindAgentApiKeyRequest req) {
        requireAdmin();
        CredentialVault vault = credentialVaultBindingService.bindAgentApiKey(
                agentId,
                req.getProvider(),
                req.getApiKey(),
                req.getExpiresAt(),
                req.getRemark()
        );
        return R.ok(toInfo(vault));
    }

    @PostMapping("/rotateByAgentId/{agentId}")
    public R<CredentialInfoResponse> rotateByAgentId(@PathVariable("agentId") Long agentId,
                                                     @RequestBody RotateAgentApiKeyRequest req) {
        requireAdmin();
        CredentialVault vault = credentialVaultBindingService.rotateAgentApiKey(
                agentId, req.getProvider(), req.getApiKey(), req.getRemark());
        return R.ok(toInfo(vault));
    }

    @PostMapping("/revoke/{id}")
    public R<Void> revoke(@PathVariable("id") Long id) {
        requireAdmin();
        credentialVaultService.revokeCredential(id, "admin");
        return R.ok();
    }

    @GetMapping("/listByAgentId/{agentId}")
    public R<List<CredentialInfoResponse>> listByAgentId(@PathVariable("agentId") Long agentId) {
        requireAdmin();
        List<CredentialInfoResponse> list = credentialVaultService.listAgentCredentials(agentId)
                .stream()
                .map(this::toInfo)
                .toList();
        return R.ok(list);
    }

    @GetMapping("/audits")
    public R<PageResult<AuditLogResponse>> listAudits(
            @RequestParam(value = "credentialId", required = false) Long credentialId,
            @RequestParam(value = "ownerType", required = false) CredentialOwnerType ownerType,
            @RequestParam(value = "ownerId", required = false) Long ownerId,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        requireAdmin();
        IPage<CredentialAuditLog> result =
                credentialVaultService.listAudits(credentialId, ownerType, ownerId, page, size);
        return R.ok(PageResult.of(result, this::toAudit));
    }

    private void requireAdmin() {
        Object type = request.getAttribute(com.helloai.api.interceptor.AuthInterceptor.AUTH_TYPE_KEY);
        if (type == null || !"admin".equals(type.toString())) {
            throw new com.helloai.common.base.BizException(403, "admin only");
        }
    }

    private CredentialInfoResponse toInfo(CredentialVault vault) {
        CredentialInfoResponse resp = new CredentialInfoResponse();
        resp.setId(vault.getId());
        resp.setOwnerType(vault.getOwnerType() != null ? vault.getOwnerType().name() : null);
        resp.setOwnerId(vault.getOwnerId());
        resp.setProvider(vault.getProvider());
        resp.setCredentialType(vault.getCredentialType() != null ? vault.getCredentialType().name() : null);
        resp.setStatus(vault.getStatus() != null ? vault.getStatus().name() : null);
        resp.setExpiresAt(vault.getExpireTime());
        resp.setHasEncryptedValue(vault.getEncryptedValue() != null && !vault.getEncryptedValue().isBlank());
        resp.setHasSecretRef(vault.getSecretRef() != null && !vault.getSecretRef().isBlank());
        resp.setCreateTime(vault.getCreateTime());
        resp.setUpdateTime(vault.getUpdateTime());
        resp.setRemark(vault.getRemark());
        return resp;
    }

    private AuditLogResponse toAudit(CredentialAuditLog log) {
        AuditLogResponse resp = new AuditLogResponse();
        resp.setId(log.getId());
        resp.setCredentialId(log.getCredentialId());
        resp.setOwnerType(log.getOwnerType() != null ? log.getOwnerType().name() : null);
        resp.setOwnerId(log.getOwnerId());
        resp.setProvider(log.getProvider());
        resp.setAction(log.getAction());
        resp.setOperator(log.getOperator());
        resp.setDetail(log.getDetail());
        resp.setCreateTime(log.getCreateTime());
        return resp;
    }
}
