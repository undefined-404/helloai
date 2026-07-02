package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "helloai.agent")
public class AgentConfigProperties {

    private String registrationToken = "helloai-reg-2024";
    private boolean allowRegistration = true;
}
