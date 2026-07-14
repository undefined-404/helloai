package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 多 Provider 统一配置入口。
 *
 * <p>每个 provider 的 base-url、default-model、HTTP 超时等参数统一收敛到这里，
 * 替代各 ProviderChatClientFactory 中散落的 {@code @Value} 注解。</p>
 *
 * <p>通过 {@code @EnableConfigurationProperties(AgentProviderProperties.class)} 激活，
 * 不依赖 {@code @Component} 扫描。</p>
 */
@Data
@ConfigurationProperties(prefix = "helloai.providers")
public class AgentProviderProperties {

    private Map<String, ProviderConfig> providers = new HashMap<>();

    /**
     * 获取指定 provider 的配置（大小写不敏感），不存在则返回默认空配置。
     */
    public ProviderConfig getConfig(String provider) {
        if (provider == null) {
            return new ProviderConfig();
        }
        for (Map.Entry<String, ProviderConfig> entry : providers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(provider)) {
                return entry.getValue();
            }
        }
        return new ProviderConfig();
    }

    @Data
    public static class ProviderConfig {
        /** API Base URL（如 https://api.deepseek.com）。 */
        private String baseUrl;
        /** 默认模型名称。 */
        private String defaultModel;
        /** HTTP 连接超时毫秒数，默认 5000。 */
        private int connectTimeoutMs = 5000;
        /** HTTP 读取超时毫秒数，默认 60000。 */
        private int readTimeoutMs = 60000;
    }
}
