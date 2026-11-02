package com.helloai.core.system.quota.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.system.quota.BalanceQueryContext;
import com.helloai.core.system.quota.BalanceSnapshot;
import com.helloai.core.system.quota.BalanceProvider;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 供应商余额查询公共骨架：组装 HTTP GET + Bearer 认证 + 统一失败语义。
 *
 * <p>从 {@link BalanceCredentialResolverRegistry} 取明文 key（provider 不关心 key 在哪），
 * 无凭据直接返回 {@code failed("未配置API Key")}；网络/解析任何失败都走
 * {@code failed(reason)}，**绝不抛异常、绝不回填 0**。</p>
 */
@Slf4j
public abstract class AbstractBalanceProvider implements BalanceProvider {

    protected final BalanceCredentialResolverRegistry credentialResolverRegistry;
    protected final HttpClient httpClient;

    protected AbstractBalanceProvider(BalanceCredentialResolverRegistry credentialResolverRegistry) {
        this.credentialResolverRegistry = credentialResolverRegistry;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 无凭据时给出可读失败原因。 */
    protected String missingKeyReason() {
        return "未配置API Key";
    }

    /**
     * 发 GET 请求取响应体；任何失败返回 {@link Optional#empty()}，
     * 由子类转成 {@code failed(reason)}。
     */
    protected Optional<String> doGet(BalanceQueryContext ctx, String url, String bearerKey) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1L, ctx.fetchTimeoutMillis())))
                    .header("Authorization", "Bearer " + bearerKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                log.warn("quota fetch HTTP {} for {}: {}", code, key(), response.body());
                return Optional.empty();
            }
            return Optional.of(response.body());
        } catch (Exception e) {
            log.warn("quota fetch {} failed: {}", key(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 统一失败态快照。 */
    protected BalanceSnapshot failed(BalanceQueryContext ctx, String reason) {
        return BalanceSnapshot.failed(key(), reason, Instant.now());
    }

    /** 原始响应转 Map 供快照表 {@code raw_payload} 落库；转换失败返回 null，不阻断取数。 */
    protected Map<String, Object> rawPayload(ObjectMapper objectMapper, JsonNode root) {
        try {
            return objectMapper.convertValue(root, new TypeReference<Map<String, Object>>() {});
        } catch (RuntimeException e) {
            log.warn("quota raw payload convert {} failed: {}", key(), e.getMessage());
            return null;
        }
    }
}