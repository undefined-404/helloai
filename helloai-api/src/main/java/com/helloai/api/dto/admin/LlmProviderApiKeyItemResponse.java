package com.helloai.api.dto.admin;

import com.helloai.common.constant.CredentialStatus;
import com.helloai.core.system.port.PlatformApiKeyView;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 平台级 API Key 条目（`M-8`「Key 列表」的一行）。
 *
 * <p>不含密文，明文仅以 {@link #masked} 脱敏形态出现；{@link #fingerprint} 承担「跨行辨识」与
 * 「切换时点名」两个用途（对齐 magpie {@code KeyInfo.ID} 的
 * {@code // a fingerprint, to name it in a switch}）。</p>
 *
 * <p>{@link #masked} 为 {@code null} 的两种合法情形：{@code secretRef} 型凭证（无明文可脱敏）、
 * 单行解密失败（best-effort，不阻断列表）。</p>
 */
@Data
public class LlmProviderApiKeyItemResponse {

    /** 凭证 ID（切换 / 停用 / 删除的目标）。 */
    private Long id;

    /** {@code ACTIVE}（启用位 / 主） / {@code DISABLED}（备位） / {@code EXPIRED}（轮换淘汰的历史行）。 */
    private String status;

    /**
     * 是否为当前启用位。
     *
     * <p>由 {@link #status} 推导，不在服务层存冗余字段（冗余状态必然漂移）；
     * 放在响应里是为了让前端不必重复「什么算启用位」这一判据。</p>
     */
    private boolean primary;

    /** 明文指纹（`M-6` 口径，24 字符，不可逆）。 */
    private String fingerprint;

    /** 脱敏展示值（`M-8` 统一口径 {@code SecretMask}：前 4 + … + 后 4）。 */
    private String masked;

    private OffsetDateTime createTime;

    private String remark;

    public static LlmProviderApiKeyItemResponse from(PlatformApiKeyView view) {
        LlmProviderApiKeyItemResponse response = new LlmProviderApiKeyItemResponse();
        response.setId(view.id());
        response.setStatus(view.status() != null ? view.status().name() : null);
        response.setPrimary(view.status() == CredentialStatus.ACTIVE);
        response.setFingerprint(view.fingerprint());
        response.setMasked(view.masked());
        response.setCreateTime(view.createTime());
        response.setRemark(view.remark());
        return response;
    }
}
