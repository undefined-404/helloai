package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台内 Agent 执行链配置。
 *
 * <p>T4/T5 默认启用 mock 模式，保证本地无需外部 LLM Key 也能稳定验证最小闭环。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.execution")
public class AgentExecutionProperties {

    /** 是否启用平台内执行链。 */
    private boolean enabled = true;

    /** 是否启用稳定 mock 模式。默认 true。 */
    private boolean mockMode = true;

    /** real 模式是否强制要求 vault 已绑定凭证。默认 false（先兼容全局 Provider 配置）。 */
    private boolean requireVault = false;

    /** mock provider 名称。 */
    private String provider = "mock";

    /** mock model 名称。 */
    private String model = "helloai-mock-executor";

    /** mock 前缀，便于联调时快速识别结果来源。 */
    private String mockResponsePrefix = "[mock-executor]";

    /** PENDING 执行记录超时分钟数，默认 5。 */
    private int pendingTimeoutMinutes = 5;

    /** RUNNING 执行记录超时分钟数，默认 10。 */
    private int runningTimeoutMinutes = 10;
}
