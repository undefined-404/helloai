package com.helloai.core.system.port;

import com.helloai.common.constant.CredentialStatus;

import java.time.OffsetDateTime;

/**
 * 平台级 API Key 条目只读快照（`M-8`，system 域对外契约）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 agent 域高于提供方 system 域
 * ⇒ 契约落【提供方】{@code system.port}；消费方不再 import {@code system.entity.CredentialVault}。
 * 同 {@link CredentialSecret} 的先例。</p>
 *
 * <p><b>最小投影</b>：只带列表渲染所必需的字段 —— <b>不含密文</b>（{@code encryptedValue} 不出域），
 * 明文只以 {@link #masked} 脱敏形态出现；{@link #fingerprint} 为不可逆指纹，承担「跨行辨识」
 * 与「切换时点名」两个用途（对齐 magpie {@code KeyInfo.ID} 的 {@code // a fingerprint, to name it in a switch}）。</p>
 *
 * <p>{@link #masked} 为 {@code null} 的两种合法情形：{@code secretRef} 型凭证（无明文可脱敏）、
 * 单行解密失败（best-effort，与 {@code backfillFingerprints} 同风格：不阻断列表）。</p>
 *
 * @param id          凭证 ID（切换 / 停用 / 删除的目标）
 * @param status      状态；{@code ACTIVE} = 该 provider 的启用位（主），{@code DISABLED} = 备位
 * @param fingerprint 明文指纹（`M-6` 口径，24 字符）
 * @param masked      脱敏展示值（`M-8` 统一口径，见 {@code SecretMask}）
 * @param createTime  创建时间（列表排序依据，也是「顶替时选哪把备位」的依据）
 * @param remark      备注
 */
public record PlatformApiKeyView(
        Long id,
        CredentialStatus status,
        String fingerprint,
        String masked,
        OffsetDateTime createTime,
        String remark
) {
}
