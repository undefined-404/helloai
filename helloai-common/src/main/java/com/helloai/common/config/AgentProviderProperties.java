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
 *
 * <p>注意：前缀必须是 {@code helloai} 而非 {@code helloai.providers}——字段名
 * {@code providers} 本身参与绑定路径。历史上误写成 {@code helloai.providers} 前缀，
 * 导致 yml 中 {@code helloai.providers.deepseek.*} 从未绑定成功，只因 deepseek
 * 各项默认值与 Factory 内置默认恰好一致而未暴露。</p>
 */
@Data
@ConfigurationProperties(prefix = "helloai")
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
        /** 平台级 API Key；配置后该 provider 才视为"已生效"，可用于手动注册平台内 LLM Agent。 */
        private String apiKey;
        /** HTTP 连接超时毫秒数，默认 5000。 */
        private int connectTimeoutMs = 5000;
        /** HTTP 读取超时毫秒数，默认 60000。 */
        private int readTimeoutMs = 60000;

        /** 是否已配置平台级 API Key。 */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
