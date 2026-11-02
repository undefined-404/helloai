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
 * MiniMax 余额（额度型）查询。
 *
 * <p>端点 {@code GET https://api.minimaxi.com/v1/token_plan/remains}，响应
 * {@code model_remains[]}，取 {@code model_name="general"} 项。MiniMax 是**额度型
 * 非金额**：写 {@code quotaPercent}/{@code rawUnit}，不写 amount。</p>
 *
 * <p>额度两档（2026-10-26 实测）：{@code current_interval_remaining_percent}
 * （周期内剩余）+ {@code current_weekly_remaining_percent}（周内剩余）。overview 展示
 * 取更紧张一档（两者取小），更贴合「快没了」判断。</p>
 *
 * <p>状态字段为 {@link #STATUS_UNLIMITED}（不限量）时未知剩余百分比，容错为 100%。</p>
 */
@Slf4j
public class MiniMaxBalanceProvider extends AbstractBalanceProvider {

    static final String BASE_URL = "https://api.minimaxi.com";

    /** 窗口状态码：该时段不限量（真实响应实测值，CODE_STYLE §12.2 禁止裸数字比较）。 */
    private static final int STATUS_UNLIMITED = 3;

    /** 不限量窗口的剩余百分比。 */
    private static final BigDecimal FULL_PERCENT = new BigDecimal("100");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public MiniMaxBalanceProvider(BalanceCredentialResolverRegistry resolverRegistry) {
        super(resolverRegistry);
    }

    @Override
    public String key() {
        return "minimax";
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
        Optional<String> body = doGet(ctx, BASE_URL + "/v1/token_plan/remains", keyOpt.get());
        if (body.isEmpty()) {
            return failed(ctx, "请求失败或非2xx响应");
        }
        try {
            JsonNode root = objectMapper.readTree(body.get());
            JsonNode remains = root.path("model_remains");
            JsonNode general = findGeneral(remains);
            if (general == null) {
                log.warn("minimax quota: no model_remains.general in body={}", body.get());
                return failed(ctx, "响应缺少model_remains.general");
            }
            BigDecimal interval = windowPercent(general,
                    "current_interval_status", "current_interval_remaining_percent");
            BigDecimal weekly = windowPercent(general,
                    "current_weekly_status", "current_weekly_remaining_percent");
            BigDecimal tighter = min(interval, weekly);
            if (tighter == null) {
                log.warn("minimax quota: no remaining percent in body={}", body.get());
                return failed(ctx, "响应缺少剩余百分比");
            }
            return BalanceSnapshot.live(
                    key(), null, null, tighter, "百分比", Instant.now())
                    .withRaw(rawPayload(objectMapper, root));
        } catch (Exception e) {
            log.warn("minimax quota parse failed: {}", e.getMessage());
            return failed(ctx, "响应解析失败");
        }
    }

    /**
     * 单档剩余百分比：状态字段为 {@link #STATUS_UNLIMITED} 表示该档不限量 → 100%；
     * 否则读 percent 字段。真实状态字段为 {@code current_interval_status} /
     * {@code current_weekly_status}（2026-11-02 真机核对，非顶层 {@code status}）。
     */
    private BigDecimal windowPercent(JsonNode general, String statusField, String percentField) {
        if (general.path(statusField).asInt(0) == STATUS_UNLIMITED) {
            return FULL_PERCENT;
        }
        return parsePercent(general.path(percentField));
    }

    private JsonNode findGeneral(JsonNode remains) {
        if (!remains.isArray()) {
            return null;
        }
        for (JsonNode node : remains) {
            if ("general".equals(node.path("model_name").asText())) {
                return node;
            }
        }
        return null;
    }

    private BigDecimal parsePercent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }
}