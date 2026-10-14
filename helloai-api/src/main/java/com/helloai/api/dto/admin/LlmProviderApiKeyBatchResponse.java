package com.helloai.api.dto.admin;

import com.helloai.core.agent.dto.ApiKeyBatchSaveOutcome;
import lombok.Data;

import java.util.List;

/**
 * 平台级 API Key **批量**保存结果（`M-7`）。
 *
 * <p>把服务层的「这批里新增了几把 / 几把已经在了」透出给前端，使一次粘贴多把 Key 后
 * 得到「新增 N 把 / 已存在 M 把」的可读结论，而不是逐把弹窗。</p>
 */
@Data
public class LlmProviderApiKeyBatchResponse {

    /** 本次新落库的把数。 */
    private int added;

    /** 命中同 provider 内已有指纹、被跳过的把数。 */
    private int alreadyExists;

    /**
     * 本批是否把**首把**升为启用位。
     *
     * <p>{@code true} = 该 provider 原本没有 ACTIVE 凭证，首把已生效；
     * {@code false} = 原本已有启用中的主 Key，本批新增**全部落在备位**
     * （不覆盖正在使用的主 Key）。备位转正属 `M-8`。</p>
     */
    private boolean primaryActivated;

    /** 新落库各把的指纹（24 字符，不可逆、不暴露明文），顺序与输入一致。 */
    private List<String> addedFingerprints;

    /** 可读结论，形如「新增 3 把 / 已存在 2 把」。 */
    private String message;

    public static LlmProviderApiKeyBatchResponse from(ApiKeyBatchSaveOutcome outcome) {
        LlmProviderApiKeyBatchResponse response = new LlmProviderApiKeyBatchResponse();
        response.setAdded(outcome.added());
        response.setAlreadyExists(outcome.alreadyExists());
        response.setPrimaryActivated(outcome.primaryActivated());
        response.setAddedFingerprints(outcome.addedFingerprints());
        response.setMessage(outcome.message());
        return response;
    }
}
