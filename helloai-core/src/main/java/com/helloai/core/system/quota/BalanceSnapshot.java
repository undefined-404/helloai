package com.helloai.core.system.quota;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 统一余额快照（含失败态）。
 *
 * <p>金额型供应商写 {@code amount}/{@code currency}（deepseek / moonshot / 博查），
 * 额度型供应商写 {@code quotaPercent}/{@code rawUnit}（MiniMax 剩余百分比），
 * 两栏严格分开，**不把百分比塞进 amount**（否则无法求和算总额、无法按「快没了」阈值判断）。</p>
 *
 * @param providerKey  供应商标识，与 credential_vault.provider 同名（小写）
 * @param available    是否有可用的金额/额度数；false 时数值字段为 null
 * @param amount       金额（金额型，available=true 时非空）
 * @param currency     币种（CNY / USD），金额型有效；额度型为 null
 * @param rawUnit      额度型供应商的单位描述，如「百分比」（MiniMax）
 * @param quotaPercent 额度型供应商的剩余百分比（0~100），如 MiniMax
 * @param fetchedAt    抓取/缓存的时刻
 * @param error        available=false 时的用户可见失败原因
 * @param source       LIVE / CACHED / FAILED，前端决定是否显示「数值」
 * @param rawPayload   解析成功的原始响应（仅落快照表供排障，不进入 API 响应与缓存）
 */
public record BalanceSnapshot(
        String providerKey,
        boolean available,
        BigDecimal amount,
        String currency,
        String rawUnit,
        BigDecimal quotaPercent,
        Instant fetchedAt,
        String error,
        Source source,
        @JsonIgnore Map<String, Object> rawPayload
) {

    /** 无原始响应的 9 参构造（缓存反序列化与既有工厂沿用）。 */
    public BalanceSnapshot(String providerKey, boolean available, BigDecimal amount, String currency,
                           String rawUnit, BigDecimal quotaPercent, Instant fetchedAt, String error, Source source) {
        this(providerKey, available, amount, currency, rawUnit, quotaPercent, fetchedAt, error, source, null);
    }

    /** 抓取结果三类态，前端据此决定「数值」的展示方式。 */
    public enum Source {
        /** 本次实时抓到 */
        LIVE,
        /** 命中缓存，非本次实时抓取 */
        CACHED,
        /** 抓取失败且无缓存；此时数值字段为 null，前端显示「—」而非 0 */
        FAILED
    }

    /** 附上原始响应（仅 LIVE 抓取结果使用）。 */
    public BalanceSnapshot withRaw(Map<String, Object> raw) {
        return new BalanceSnapshot(providerKey, available, amount, currency, rawUnit, quotaPercent,
                fetchedAt, error, source, raw);
    }

    public static BalanceSnapshot live(
            String providerKey,
            BigDecimal amount,
            String currency,
            BigDecimal quotaPercent,
            String rawUnit,
            Instant fetchedAt) {
        return new BalanceSnapshot(
                providerKey, true, amount, currency, rawUnit, quotaPercent, fetchedAt, null, Source.LIVE);
    }

    public static BalanceSnapshot failed(String providerKey, String reason, Instant fetchedAt) {
        return new BalanceSnapshot(
                providerKey, false, null, null, null, null, fetchedAt, reason, Source.FAILED);
    }

    public static BalanceSnapshot cached(BalanceSnapshot origin, Instant fetchedAt) {
        return new BalanceSnapshot(
                origin.providerKey(), origin.available(),
                origin.amount(), origin.currency(), origin.rawUnit(),
                origin.quotaPercent(), fetchedAt, origin.error(), Source.CACHED, origin.rawPayload());
    }
}