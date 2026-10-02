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

    /**
     * Agent 自注册共享 token。
     *
     * <p>§42：token 不得写入代码。此处**不设默认值**；值由配置项
     * {@code helloai.agent.registration-token} 注入，而该配置项在 application.yml 中已是
     * 无默认占位符（§41 例外分支：缺失即启动失败），故不存在「代码内兜底弱 token」路径。</p>
     */
    private String registrationToken;

    private boolean allowRegistration = true;
}
