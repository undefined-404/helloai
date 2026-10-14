package com.helloai.api.dto.agent;

import lombok.Data;

/**
 * API Key 响应。
 *
 * <p>`M-6` 起附 {@link #fingerprint} 指纹：明文的 SHA-256 前缀（24 字符），
 * 用于同 Key 去重 / 多 Key 区分，**不可逆、不暴露明文**。</p>
 */
@Data
public class ApiKeyResponse {

    /** 明文 Key；**仅**重置（resetKey）等一次性回显场景填充。 */
    private String apiKey;

    /** Key 指纹：SHA-256 前缀十六进制串（24 字符）。 */
    private String fingerprint;
}
