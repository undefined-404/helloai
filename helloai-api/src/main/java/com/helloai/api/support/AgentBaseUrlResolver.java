package com.helloai.api.support;

import com.helloai.common.config.AgentConfigProperties;
import com.helloai.core.system.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent 对接地址（BASE_URL）统一解析器，收敛外网地址断层。
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>{@code sys_config["helloai.base-url"]}（系统设置页可写，管理员维护的外网地址）</li>
 *   <li>yml {@code helloai.agent.base-url}（部署级显式配置）</li>
 *   <li>请求推导 {@code scheme://serverName:port}（nginx 反代时需配置 X-Forwarded-* 才能得到外网地址）</li>
 *   <li>兜底 {@code http://localhost:6565}</li>
 * </ol>
 *
 * <p>供 {@code AgentController.getMySkill} / {@code AdminAgentController.getOnboardingContent}
 * 等生成 SKILL / 接入内容时统一调用，避免各 Controller 各自 fallback 导致地址不一致。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentBaseUrlResolver {

    /** sys_config 中的平台外网地址键名（与 Settings.vue 写入键一致）。 */
    public static final String SYS_CONFIG_KEY = "helloai.base-url";

    /** 最终兜底地址。 */
    public static final String FALLBACK_BASE_URL = "http://localhost:6565";

    private final SysConfigService sysConfigService;
    private final AgentConfigProperties agentConfigProperties;

    /**
     * 解析 Agent 对接地址。
     *
     * @param request 当前请求（可为 null；为 null 时跳过请求推导，直接回退兜底地址）
     */
    public String resolve(HttpServletRequest request) {
        String sysValue = sysConfigService.getValue(SYS_CONFIG_KEY);
        if (sysValue != null && !sysValue.isBlank()) {
            return sysValue;
        }
        String configured = agentConfigProperties.getBaseUrl();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (request != null) {
            return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        }
        return FALLBACK_BASE_URL;
    }
}
