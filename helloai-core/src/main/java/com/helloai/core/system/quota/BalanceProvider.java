package com.helloai.core.system.quota;

/**
 * 供应商余额查询 SPI。
 *
 * <p>三条硬约束（每条均有具体原因）：</p>
 * <ol>
 *   <li>{@code fetch} **不抛异常**，失败一律返回 {@code BalanceSnapshot.failed(reason)}。
 *       单家失败不能拖垮整个 overview；注册表遍历时不写 try/catch。</li>
 *   <li>{@code key()} 与 {@code credential_vault.provider} 同名（小写）。
 *       两个标识体系分叉后，加供应商要改两处。</li>
 *   <li>{@code supports()} 独立于 {@code fetch()}，用于「有凭据但没有接口」的情形
 *       （如通义未来补上 AK/SK 时改 supports=true 即可，不动调用方）。</li>
 * </ol>
 */
public interface BalanceProvider {

    /** 供应商标识，与 credential_vault.provider 对齐（小写），如 deepseek / minimax。 */
    String key();

    /** 是否支持查询；不支持时 overview 会跳过而非报错。 */
    boolean supports(BalanceQueryContext ctx);

    /** 查询；任何失败都返回 {@link BalanceSnapshot#failed} 而非抛异常。 */
    BalanceSnapshot fetch(BalanceQueryContext ctx);
}