package com.helloai.api.dto.credential;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class CredentialInfoResponse {

    private Long id;
    private String ownerType;
    private Long ownerId;
    private String provider;
    private String credentialType;
    private String status;
    private OffsetDateTime expiresAt;
    private boolean hasEncryptedValue;
    private boolean hasSecretRef;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private String remark;
}

