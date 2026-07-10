package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "helloai.security.credential")
public class CredentialCryptoProperties {

    private String aesKeyBase64;
}
