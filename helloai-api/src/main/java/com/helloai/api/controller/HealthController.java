package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.common.config.AgentExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查 / 运行期只读探针。
 *
 * <p>{@code /api/health/**} 已在 {@code WebMvcConfig} 中被认证拦截器放行，
 * 属于公开只读入口，可供部署探针与 E2E 脚本 pre-flight 使用。</p>
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AgentExecutionProperties executionProperties;

    @GetMapping("/api/health")
    public R<Map<String, String>> health() {
        return R.ok(Map.of(
                "status", "ok",
                "service", "HelloAI",
                "version", "1.0.0"
        ));
    }

    /**
     * 运行期执行模式只读探针。
     *
     * <p>回显 live app 的 {@link AgentExecutionProperties} 关键开关，供 E2E 脚本在
     * pre-flight 阶段硬断言 mock 执行是否开启，避免脚本无意中触发真实 LLM。
     * 仅暴露布尔开关与 provider/model 名称，不涉及任何密钥或凭证。</p>
     */
    @GetMapping("/api/health/getExecutionMode")
    public R<Map<String, Object>> getExecutionMode() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", executionProperties.isEnabled());
        body.put("mockMode", executionProperties.isMockMode());
        body.put("requireVault", executionProperties.isRequireVault());
        body.put("provider", executionProperties.getProvider());
        body.put("model", executionProperties.getModel());
        return R.ok(body);
    }
}
