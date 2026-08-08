package com.helloai.api.dto.admin;

import lombok.Data;

/**
 * 平台级 Provider API Key 写入请求。
 */
@Data
public class ProviderApiKeyRequest {

    /** 新的 API Key 明文（覆盖旧 Key）。 */
    private String apiKey;
}
