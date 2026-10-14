package com.helloai.core.shared.util;

/**
 * 密钥脱敏展示（`M-8`）。
 *
 * <p>口径逐字对齐 magpie {@code internal/provider/provider.go:1501-1506} 的 {@code Mask}：
 * <b>长度 ≤ 8 时整串替换为等长点号；否则保留前 4 位与后 4 位，中间以省略号相连</b>。
 * 目的与 magpie 的 {@code KeyInfo.Masked} 一致 —— 让「一个 provider 有多把 Key」的列表
 * <b>逐行可辨识</b>（仅凭指纹无法分辨），同时不暴露完整明文。</p>
 *
 * <p><b>为何是工具类而非各调用点各写一份</b>：同一件事（脱敏口径）在两处各写一个判据
 * 必然分叉。本类是全仓 <b>唯一</b> 的密钥脱敏口径，`M-8` 起 {@code PlatformProviderConfigService#maskApiKey}
 * 亦改走此处（原为自有的 {@code "****" + 尾 4}）。</p>
 *
 * <p><b>与 {@code SysConfigServiceImpl.SENSITIVE_MASKED} 的区别（有意保留，非分叉）</b>：
 * 后者是「任意敏感<b>配置值</b>」的整体遮蔽（{@code "********"}），不保留任何字符，
 * 且不区分具体密钥结构；本类只服务「Agent / Provider 的 API Key」这一类已知结构的凭证。</p>
 */
public final class SecretMask {

    /** 两侧保留的字符数。 */
    private static final int EDGE = 4;

    /** 触发「保留两侧」的最短长度；不超过此长度则整串遮蔽。 */
    private static final int MIN_LENGTH_FOR_EDGE = 8;

    /** 整串遮蔽时使用的字符（对齐 magpie）。 */
    private static final String BULLET = "•";

    /** 前 4 / 后 4 之间的连接符（对齐 magpie）。 */
    private static final String ELLIPSIS = "…";

    private SecretMask() {
    }

    /**
     * 脱敏。
     *
     * @param secret 明文密钥；{@code null} / 空串原样返回（无秘密可遮蔽，避免把「未配置」渲染成点号串）
     * @return 脱敏串
     */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return secret;
        }
        int length = secret.length();
        if (length <= MIN_LENGTH_FOR_EDGE) {
            return BULLET.repeat(length);
        }
        return secret.substring(0, EDGE) + ELLIPSIS + secret.substring(length - EDGE);
    }
}
