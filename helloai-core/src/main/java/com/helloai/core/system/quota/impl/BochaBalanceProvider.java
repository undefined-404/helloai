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
 * 博查余额查询。
 *
 * <p>端点 {@code GET {base}/v1/fund/remaining}，响应 {@code data.remaining}（金额型）。
 * 余额端点与搜索端点同域；base 域名可配（V2 新旧文档差异：{@code api.bochaai.com} 主用、
 * {@code api.bocha.cn} 回落），主域失败自动回落备用域。</p>
 *
 * <p><b>单位口径</b>：官方文档标注「单位：元，保留两位小数」，故按原值入 {@code amount}、
 * 不做 /100 换算；{@code currency} 暂为 null（本地无 key 未能真机复核，不猜测币种）。</p>
 */
@Slf4j
public class BochaBalanceProvider extends AbstractBalanceProvider {

    /** 余额端点候选 base（主 / 回落）。 */
    static final String[] CANDIDATE_BASES = {
            "https://api.bochaai.com",
            "https://api.bocha.cn"
    };

    private final ObjectMapper objectMapper = new ObjectMapper();

    public BochaBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        super(resolverRegistry);
    }

    @Override
    public String key() {
        return "bocha";
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
        String body = tryFetch(ctx, keyOpt.get());
        if (body == null) {
            return failed(ctx, "请求失败或非2xx响应");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode remaining = root.path("data").path("remaining");
            if (remaining.isMissingNode() || remaining.isNull()) {
                log.warn("bocha quota: no data.remaining in body={}", body);
                return failed(ctx, "响应缺少data.remaining");
            }
            BigDecimal amount = new BigDecimal(remaining.asText());
            // 官方口径为元；currency 未真机复核，留 null 不猜测
            return BalanceSnapshot.live(
                    key(), amount, null, null, null, Instant.now())
                    .withRaw(rawPayload(objectMapper, root));
        } catch (Exception e) {
            log.warn("bocha quota parse failed: {}", e.getMessage());
            return failed(ctx, "响应解析失败");
        }
    }

    /** 依次尝试候选域名，任一成功即返回响应体；全失败返回 null。 */
    private String tryFetch(BalanceQueryContext ctx, String bearerKey) {
        for (String base : CANDIDATE_BASES) {
            Optional<String> body = doGet(ctx, base + "/v1/fund/remaining", bearerKey);
            if (body.isPresent()) {
                return body.get();
            }
        }
        return null;
    }
}