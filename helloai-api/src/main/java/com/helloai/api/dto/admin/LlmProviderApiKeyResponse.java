package com.helloai.api.dto.admin;

import com.helloai.core.agent.dto.ApiKeySaveOutcome;
import lombok.Data;

/**
 * 平台级 API Key 保存结果（`M-6`）。
 *
 * <p>把服务层的「本次是新增还是命中已有 Key」透出给前端，使「同一 Key 二次录入」
 * 得到可读提示，而不是静默再落一行。</p>
 */
@Data
public class LlmProviderApiKeyResponse {

    /** 是否本次新增（{@code false} = 同 provider 下该 Key 已存在，未重复保存）。 */
    private boolean created;

    /**
     * Key 指纹：明文的 SHA-256 前缀十六进制串（24 字符）。
     * **不可逆、不暴露明文**，供前端做「同 Key 识别 / 多 Key 区分」展示。
     */
    private String fingerprint;

    /** 可读结论，如「该 API Key 已存在，未重复保存」。 */
    private String message;

    public static LlmProviderApiKeyResponse from(ApiKeySaveOutcome outcome) {
        LlmProviderApiKeyResponse response = new LlmProviderApiKeyResponse();
        response.setCreated(outcome.created());
        response.setFingerprint(outcome.fingerprint());
        response.setMessage(outcome.message());
        return response;
    }
}
