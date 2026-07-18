package com.helloai.api.controller;

import com.helloai.api.dto.credential.BindAgentApiKeyRequest;
import com.helloai.api.dto.credential.CredentialInfoResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialVaultBindingService credentialVaultBindingService;
    private final CredentialVaultService credentialVaultService;
    private final HttpServletRequest request;

    @PostMapping("/agents/{agentId}/api-key")
    public R<CredentialInfoResponse> bindAgentApiKey(@PathVariable("agentId") Long agentId,
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

    @GetMapping("/agents/{agentId}")
    public R<List<CredentialInfoResponse>> listAgentCredentials(@PathVariable("agentId") Long agentId) {
        requireAdmin();
        List<CredentialInfoResponse> list = credentialVaultService.lambdaQuery()
                .eq(CredentialVault::getOwnerType, CredentialOwnerType.AGENT)
                .eq(CredentialVault::getOwnerId, agentId)
                .list()
                .stream()
                .map(this::toInfo)
                .toList();
        return R.ok(list);
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
        resp.setExpiresAt(vault.getExpiresAt());
        resp.setHasEncryptedValue(vault.getEncryptedValue() != null && !vault.getEncryptedValue().isBlank());
        resp.setHasSecretRef(vault.getSecretRef() != null && !vault.getSecretRef().isBlank());
        resp.setCreateTime(vault.getCreateTime());
        resp.setUpdateTime(vault.getUpdateTime());
        resp.setRemark(vault.getRemark());
        return resp;
    }
}
