package com.helloai.core.planner.search;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 联网搜索供应商 API Key 解析（系统设置页可写）。
 *
 * <p>解析优先级（仿 {@code AgentBaseUrlResolver} 模式）：</p>
 * <ol>
 *   <li>{@code sys_config["web-search.bocha.api-key"]}：设置页写入的加密值
 *       （{@code enc:}{AES-GCM-Base64}），解密后返回；明文（含历史遗留）原样兼容；</li>
 *   <li>{@link WebSearchProperties#getBochaApiKey()}：yml / 环境变量部署级兜底。</li>
 * </ol>
 *
 * <p>历史坑：{@code WebSearchProperties.bochaApiKey} 默认值为字面量
 * {@code "${BOCHA_API_KEY:}"}——无 yml 条目时占位符不解析，字面量会被直接当
 * Bearer token 发出且 {@code isBlank()} 拦不住。本类解析时剔除该残留字面量，
 * 保证"未配置 = 未启用"语义。空串视为清除（供应商未启用）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchCredentialKeyStore {

    /** sys_config 中的博查 API Key 键名（与系统设置页写入键一致）。 */
    public static final String BOCHA_API_KEY_CONFIG_KEY = "web-search.bocha.api-key";

    /** 加密存储形态前缀（与 CredentialCryptoService 产物约定一致）。 */
    private static final String ENCRYPTED_PREFIX = "enc:";

    /** WebSearchProperties 默认值残留的占位符字面量，视为未配置。 */
    private static final String PLACEHOLDER_LITERAL = "${BOCHA_API_KEY:}";

    private final SysConfigService sysConfigService;
    private final CredentialCryptoService credentialCryptoService;
    private final WebSearchProperties properties;

    /** 保存博查 API Key：加密后写 sys_config；blank 视为清除（写空串）。 */
    public void saveBochaApiKey(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            sysConfigService.setValue(BOCHA_API_KEY_CONFIG_KEY, "");
            log.info("博查 API Key 已清除（系统设置页）");
            return;
        }
        sysConfigService.setValue(BOCHA_API_KEY_CONFIG_KEY,
                ENCRYPTED_PREFIX + credentialCryptoService.encryptToBase64(plainKey));
        log.info("博查 API Key 已更新（系统设置页，加密存储）");
    }

    /** 博查 API Key 是否已配置（设置页状态展示用，不返回明文）。 */
    public boolean hasBochaApiKey() {
        String key = resolveBochaApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * 解析博查 API Key 明文；未配置返回 null。
     */
    public String resolveBochaApiKey() {
        String stored = sysConfigService.getValue(BOCHA_API_KEY_CONFIG_KEY);
        if (stored != null && !stored.isBlank()) {
            if (stored.startsWith(ENCRYPTED_PREFIX)) {
                try {
                    return credentialCryptoService.decryptFromBase64(stored.substring(ENCRYPTED_PREFIX.length()));
                } catch (Exception e) {
                    log.warn("博查 API Key 解密失败，回退部署级配置: err={}", e.getMessage());
                }
            } else {
                return stored;
            }
        }
        String fallback = properties.getBochaApiKey();
        if (fallback == null || fallback.isBlank() || PLACEHOLDER_LITERAL.equals(fallback)) {
            return null;
        }
        return fallback;
    }
}
