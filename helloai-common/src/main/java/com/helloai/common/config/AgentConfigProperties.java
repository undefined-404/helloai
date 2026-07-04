package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "helloai.agent")
public class AgentConfigProperties {

    /** 服务外网访问地址（Agent 对接用），为空时从请求自动推导 */
    private String baseUrl;
    private String registrationToken = "helloai-reg-2024";
    private boolean allowRegistration = true;
}
