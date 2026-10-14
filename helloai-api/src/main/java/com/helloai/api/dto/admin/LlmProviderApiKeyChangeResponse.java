package com.helloai.api.dto.admin;

import com.helloai.core.system.port.PlatformApiKeyChange;
import lombok.Data;

import java.util.Objects;

/**
 * 平台级 API Key 主备操作结果（`M-8`：切换 / 停用 / 删除）。
 *
 * <p>如实交出「操作前后的启用位是谁」，使前端能提示「启用位已切换到 …」或
 * 「仅删除了备位，启用位未变」，而不必再查一次列表。</p>
 */
@Data
public class LlmProviderApiKeyChangeResponse {

    /** 本次操作的目标凭证 ID（切换 = 被升为启用位的那把；停用 / 删除 = 目标本身）。 */
    private Long credentialId;

    /** 目标凭证指纹（不可逆，24 字符）。 */
    private String fingerprint;

    /** 操作前的启用位 ID；此前无启用位时为 {@code null}。 */
    private Long previousActiveId;

    /** 操作后的启用位 ID。 */
    private Long activeId;

    /**
     * 本次操作是否改变了「哪一把是启用位」。
     *
     * <p>由 {@link #previousActiveId} 与 {@link #activeId} 推导（不在服务层存冗余字段）；
     * 放在响应里同样是为了让前端不必重复这条判据。</p>
     */
    private boolean activeChanged;

    public static LlmProviderApiKeyChangeResponse from(PlatformApiKeyChange change) {
        LlmProviderApiKeyChangeResponse response = new LlmProviderApiKeyChangeResponse();
        response.setCredentialId(change.credentialId());
        response.setFingerprint(change.fingerprint());
        response.setPreviousActiveId(change.previousActiveId());
        response.setActiveId(change.activeId());
        response.setActiveChanged(!Objects.equals(change.previousActiveId(), change.activeId()));
        return response;
    }
}
