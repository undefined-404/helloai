package com.helloai.core.agent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.service.LlmProviderKeyVerifyService;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Provider API Key 连通性验证实现（raw HTTP 最小探测）。
 *
 * <p>按协议类型探测端点：</p>
 * <ul>
 *   <li>OPENAI_COMPATIBLE：POST {baseUrl}/v1/chat/completions，Bearer 认证；</li>
 *   <li>ANTHROPIC_COMPATIBLE：POST {baseUrl}/v1/messages，x-api-key 认证 +
 *       {@code anthropic-version} 头。</li>
 * </ul>
 *
 * <p>请求体固定为最小载荷（{@code max_tokens=1} + 单条 "ping" 消息），
 * 连接超时 5s / 请求超时 20s，避免验证操作拖慢设置页。</p>
 *
 * <p>模型取值：{@code llm_provider.default_model} &gt; 内置供应商兜底表；
 * 均为空时不发起请求，直接返回失败（提示先配置默认模型）。</p>
 */
@Slf4j
@Service
public class LlmProviderKeyVerifyServiceImpl implements LlmProviderKeyVerifyService {

    private static final String PROTOCOL_ANTHROPIC = "ANTHROPIC_COMPATIBLE";

    /** 内置供应商兜底模型（default_model 为空时使用，仅作最小探测）。 */
    private static final Map<String, String> FALLBACK_MODELS = Map.of(
            "deepseek", "deepseek-chat",
            "moonshot", "moonshot-v1-8k",
            "minimax", "MiniMax-Text-01",
            "dashscope", "qwen-turbo"
    );

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final LlmProviderService providerService;
    private final PlatformProviderConfigService platformProviderConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmProviderKeyVerifyServiceImpl(LlmProviderService providerService,
                                           PlatformProviderConfigService platformProviderConfigService,
                                           ObjectMapper objectMapper) {
        this.providerService = providerService;
        this.platformProviderConfigService = platformProviderConfigService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public Map<String, Object> verifyById(Long providerId) {
        long start = System.currentTimeMillis();
        LlmProvider provider = providerService.getById(providerId);
        if (provider == null) {
            return result(false, "Provider 不存在", null, start);
        }
        String code = provider.getProviderCode();
        String apiKey = platformProviderConfigService.getApiKey(code);
        if (apiKey == null || apiKey.isBlank()) {
            return result(false, "尚未配置 API Key，请先保存密钥再验证", null, start);
        }
        String baseUrl = platformProviderConfigService.getBaseUrl(code);
        if (baseUrl == null || baseUrl.isBlank()) {
            return result(false, "尚未配置 Base URL，无法发起验证", null, start);
        }
        String model = platformProviderConfigService.getDefaultModel(code);
        if (model == null || model.isBlank()) {
            model = FALLBACK_MODELS.get(code);
        }
        if (model == null || model.isBlank()) {
            return result(false, "未配置默认模型，请先在模型管理中设置后再验证", null, start);
        }
        try {
            HttpRequest request = buildRequest(provider, baseUrl, apiKey, model);
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsed = System.currentTimeMillis() - start;
            if (response.statusCode() / 100 == 2) {
                return result(true, "验证通过，API Key 有效（" + elapsed + "ms）", model, start);
            }
            boolean authFail = response.statusCode() == 401 || response.statusCode() == 403;
            String detail = extractErrorMessage(response.body());
            String msg = (authFail ? "API Key 无效或无权限" : "验证失败（HTTP " + response.statusCode() + "）")
                    + (detail.isBlank() ? "" : "：" + detail);
            log.warn("LLM Provider Key 验证未通过: provider={}, status={}, body={}",
                    code, response.statusCode(), truncate(response.body(), 200));
            return result(false, msg, model, start);
        } catch (Exception e) {
            log.warn("LLM Provider Key 验证异常（已降级为失败结果）: provider={}, err={}",
                    code, e.getMessage());
            return result(false, "验证请求失败：" + e.getMessage(), model, start);
        }
    }

    /** 按协议类型构造最小探测请求。 */
    private HttpRequest buildRequest(LlmProvider provider, String baseUrl,
                                     String apiKey, String model) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "max_tokens", 1,
                "messages", List.of(Map.of("role", "user", "content", "ping"))
        ));
        String endpoint = buildEndpoint(provider.getProtocolType(), baseUrl);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (PROTOCOL_ANTHROPIC.equals(provider.getProtocolType())) {
            builder.header("x-api-key", apiKey);
            builder.header("anthropic-version", "2023-06-01");
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    /** 拼接探测端点：Base URL 已含 /v1 时不再重复拼接。 */
    private static String buildEndpoint(String protocolType, String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (PROTOCOL_ANTHROPIC.equals(protocolType)) {
            return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        }
        return base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
    }

    /** 从错误响应中提取可读信息（兼容 message / error.message / msg 三种常见字段）。 */
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode root = objectMapper.readTree(body);
            for (String path : new String[]{"message", "msg"}) {
                String v = textOrNull(root.path(path));
                if (v != null) return truncate(v, 120);
            }
            String v = textOrNull(root.path("error").path("message"));
            if (v != null) return truncate(v, 120);
        } catch (Exception ignore) {
            // 非 JSON 响应（网关纯文本等），直接截断返回
        }
        return truncate(body, 120);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Map<String, Object> result(boolean success, String message,
                                              String model, long start) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success);
        out.put("message", message);
        out.put("model", model);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }
}
