package com.helloai.core.system.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 凭证绑定服务实现。
 */
@Service
@RequiredArgsConstructor
public class CredentialVaultBindingServiceImpl implements CredentialVaultBindingService {

    private final CredentialVaultService credentialVaultService;
    private final CredentialCryptoService credentialCryptoService;

    // #region debug-point redispatch-stuck-blocked
    private static final ObjectMapper DBG_MAPPER = new ObjectMapper();
    private static final HttpClient DBG_HTTP = HttpClient.newHttpClient();
    private static volatile String DBG_URL;

    private static String dbgUrl() {
        if (DBG_URL != null) {
            return DBG_URL;
        }
        synchronized (CredentialVaultBindingServiceImpl.class) {
            if (DBG_URL != null) {
                return DBG_URL;
            }
            String envUrl = System.getenv("DEBUG_SERVER_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                DBG_URL = envUrl;
                return DBG_URL;
            }
            try {
                Path envFile = Path.of(".dbg", "redispatch-stuck-blocked.env");
                if (Files.exists(envFile)) {
                    for (String line : Files.readAllLines(envFile)) {
                        if (line.startsWith("DEBUG_SERVER_URL=")) {
                            String url = line.substring("DEBUG_SERVER_URL=".length()).trim();
                            if (!url.isBlank()) {
                                DBG_URL = url;
                                return DBG_URL;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
                // best-effort：调试配置读取失败即放弃，不影响主链路
            }
            return null;
        }
    }

    private static void dbg(String point, Map<String, Object> data) {
        String url = dbgUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            Map<String, Object> evt = new HashMap<>();
            evt.put("sessionId", "redispatch-stuck-blocked");
            evt.put("point", point);
            evt.put("ts", OffsetDateTime.now().toString());
            evt.put("data", data != null ? data : Map.of());
            String body = DBG_MAPPER.writeValueAsString(evt);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            DBG_HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
            // best-effort：调试上报失败忽略，不影响执行链路
        }
    }
    // #endregion debug-point redispatch-stuck-blocked

    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault bindAgentApiKey(Long agentId, String provider, String apiKeyPlaintext,
                                           OffsetDateTime expiresAt, String remark) {
        if (agentId == null) {
            throw new BizException("agentId 不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String encrypted = credentialCryptoService.encryptToBase64(apiKeyPlaintext);
        return credentialVaultService.saveAgentApiKeyCredential(agentId, provider, encrypted, null, expiresAt, remark);
    }

    @Override
    public String getAgentApiKeyPlaintext(Long agentId, String provider) {
        dbg("vault_get_plaintext_enter", Map.of(
                "agentId", agentId,
                "provider", provider
        ));
        try {
            CredentialVault vault = credentialVaultService.getActiveAgentApiKey(agentId, provider);
            dbg("vault_get_plaintext_after_query", Map.of(
                    "agentId", agentId,
                    "provider", provider,
                    "found", vault != null,
                    "hasSecretRef", vault != null && vault.getSecretRef() != null && !vault.getSecretRef().isBlank(),
                    "hasEncrypted", vault != null && vault.getEncryptedValue() != null && !vault.getEncryptedValue().isBlank()
            ));
            if (vault == null) {
                return null;
            }
            String secretRef = vault.getSecretRef();
            if (secretRef != null && !secretRef.isBlank()) {
                dbg("vault_get_plaintext_use_secret_ref", Map.of(
                        "agentId", agentId,
                        "provider", provider,
                        "secretRef", secretRef
                ));
                String env = System.getenv(secretRef);
                if (env == null || env.isBlank()) {
                    throw new BizException("secretRef 指向的环境变量为空: " + secretRef);
                }
                dbg("vault_get_plaintext_secret_ref_ok", Map.of(
                        "agentId", agentId,
                        "provider", provider,
                        "len", env.length()
                ));
                return env;
            }
            String encrypted = vault.getEncryptedValue();
            if (encrypted == null || encrypted.isBlank()) {
                throw new BizException("vault 凭证缺少 encrypted_value/secret_ref");
            }
            dbg("vault_get_plaintext_before_decrypt", Map.of(
                    "agentId", agentId,
                    "provider", provider,
                    "encryptedLen", encrypted.length()
            ));
            String plaintext = credentialCryptoService.decryptFromBase64(encrypted);
            dbg("vault_get_plaintext_after_decrypt", Map.of(
                    "agentId", agentId,
                    "provider", provider,
                    "len", plaintext != null ? plaintext.length() : 0
            ));
            return plaintext;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            dbg("vault_get_plaintext_exception", Map.of(
                    "agentId", agentId,
                    "provider", provider,
                    "exception", e.getClass().getName(),
                    "message", e.getMessage(),
                    "rootException", root.getClass().getName(),
                    "rootMessage", root.getMessage()
            ));
            throw e;
        }
    }

    /**
     * 轮换 Agent 的 API Key 凭证。
     *
     * <p>AgentHub 旧凭证 → EXPIRED，新凭证 → ACTIVE。</p>
     * <p>与 {@link #bindAgentApiKey} 的区别：</p>
     * <ul>
     *   <li>bindAgentApiKey：旧凭证 → DISABLED（人为停用语义）</li>
     *   <li>rotateAgentApiKey：旧凭证 → EXPIRED（自动轮换语义），
     *       在 remark 中记录 rotated_from_id 审计链</li>
     * </ul>
     *
     * @param agentId         Agent ID
     * @param provider        LLM Provider
     * @param apiKeyPlaintext 新 API Key 明文
     * @param remark          审计备注
     * @return 新创建的 ACTIVE 凭证
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CredentialVault rotateAgentApiKey(Long agentId, String provider,
                                             String apiKeyPlaintext, String remark) {
        if (agentId == null) {
            throw new BizException("agentId 不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new BizException("provider 不能为空");
        }
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String encrypted = credentialCryptoService.encryptToBase64(apiKeyPlaintext);
        return credentialVaultService.rotateAgentApiKey(agentId, provider, encrypted, null, remark);
    }
}
