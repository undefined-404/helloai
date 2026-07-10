package com.helloai.api.dto.credential;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class BindAgentApiKeyRequest {

    private String provider;
    private String apiKey;
    private OffsetDateTime expiresAt;
    private String remark;
}

