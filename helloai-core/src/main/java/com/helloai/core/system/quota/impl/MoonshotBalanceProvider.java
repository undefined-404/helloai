package com.helloai.core.system.quota.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.system.quota.BalanceQueryContext;
import com.helloai.core.system.quota.BalanceSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Moonshot / Kimi 余额查询。
 *
 * <p>端点 {@code GET https://api.moonshot.cn/v1/users/me/balance}，响应
 * {@code data.available_balance}（金额型）。</p>
 */
@Slf4j
public class MoonshotBalanceProvider extends AbstractBalanceProvider {

    static final String BASE_URL = "https://api.moonshot.cn";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public MoonshotBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        super(resolverRegistry);
    }

    @Override
    public String key() {
        return "moonshot";
    }

    @Override
    public boolean supports(BalanceQueryContext ctx) {
        return credentialResolverRegistry.resolve(key()).isPresent();
    }

    @Override
    public BalanceSnapshot fetch(BalanceQueryContext ctx) {
        Optional<String> keyOpt = credentialResolverRegistry.resolve(key());
        if (keyOpt.isEmpty()) {
            return failed(ctx, missingKeyReason());
        }
        Optional<String> body = doGet(ctx, BASE_URL + "/v1/users/me/balance", keyOpt.get());
        if (body.isEmpty()) {
            return failed(ctx, "请求失败或非2xx响应");
        }
        try {
            JsonNode root = objectMapper.readTree(body.get());
            BigDecimal balance = new BigDecimal(root.path("data").path("available_balance").asText("0"));
            String currency = root.path("data").path("currency").asText(null);
            return BalanceSnapshot.live(
                    key(), balance, currency, null, null, Instant.now())
                    .withRaw(rawPayload(objectMapper, root));
        } catch (Exception e) {
            log.warn("moonshot quota parse failed: {}", e.getMessage());
            return failed(ctx, "响应解析失败");
        }
    }
}