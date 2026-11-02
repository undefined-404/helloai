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
 * DeepSeek 余额查询。
 *
 * <p>端点 {@code GET https://api.deepseek.com/user/balance}，响应**根级**
 * {@code balance_infos[]}（2026-11-02 真机核对，不在 {@code data} 下），取
 * {@code total_balance}（金额型，含 currency）。</p>
 */
@Slf4j
public class DeepSeekBalanceProvider extends AbstractBalanceProvider {

    static final String BASE_URL = "https://api.deepseek.com";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        super(resolverRegistry);
    }

    @Override
    public String key() {
        return "deepseek";
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
        Optional<String> body = doGet(ctx, BASE_URL + "/user/balance", keyOpt.get());
        if (body.isEmpty()) {
            return failed(ctx, "请求失败或非2xx响应");
        }
        try {
            JsonNode root = objectMapper.readTree(body.get());
            // 真实响应为根级 balance_infos[]（2026-11-02 真机核对），非 data.balance_infos
            JsonNode infos = root.path("balance_infos");
            if (infos.isArray() && infos.size() > 0) {
                JsonNode first = infos.get(0);
                BigDecimal total = new BigDecimal(first.path("total_balance").asText("0"));
                String currency = first.path("currency").asText(null);
                return BalanceSnapshot.live(
                        key(), total, currency, null, null, Instant.now())
                        .withRaw(rawPayload(objectMapper, root));
            }
            log.warn("deepseek quota: no balance_infos in body={}", body.get());
            return failed(ctx, "响应缺少balance_infos");
        } catch (Exception e) {
            log.warn("deepseek quota parse failed: {}", e.getMessage());
            return failed(ctx, "响应解析失败");
        }
    }
}