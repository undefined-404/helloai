package com.helloai.api.dto.credential;

import lombok.Data;

/**
 * Agent API Key 轮换请求（Phase 2 B2，N-004 收口）。
 */
@Data
public class RotateAgentApiKeyRequest {

    /** LLM Provider 标识。 */
    private String provider;

    /** 新 API Key 明文（加密后落库）。 */
    private String apiKey;

    /** 审计备注。 */
    private String remark;
}
