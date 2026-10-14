package com.helloai.core.agent.dto;

import java.util.List;

/**
 * 批量保存 API Key 的结果（`M-7`）。
 *
 * <p>与单把的 {@link ApiKeySaveOutcome} 并列：单把回答「这一把是新落库还是已存在」，
 * 本记录回答「这批里有几把新的、几把已经在了」。</p>
 *
 * <p><b>语义</b>（对齐 magpie {@code internal/provider/keys.go:183-222} 的 {@code AddKeys}
 * 返回的 {@code (added, had)}）：</p>
 * <ul>
 *   <li>{@code added} —— 本次**新落库**的把数；</li>
 *   <li>{@code alreadyExists} —— 命中同 provider 内已有指纹、**被跳过**的把数；</li>
 *   <li>{@code primaryActivated} —— 本批是否触发了「启用位」变更（即该 provider 原本没有
 *       ACTIVE 凭证，由本批首把升为 ACTIVE）。已有主 Key ⇒ 恒为 {@code false}，因为
 *       magpie 的 {@code AddKeys} 在 {@code p.Key != ""} 时**只往备位追加、不覆盖主 key**。</li>
 * </ul>
 *
 * @param added              新落库把数
 * @param alreadyExists      已存在（跳过）把数
 * @param primaryActivated   是否本次把首把升为启用位
 * @param addedFingerprints  新落库各把的指纹（不暴露明文），顺序与输入一致
 */
public record ApiKeyBatchSaveOutcome(int added, int alreadyExists, boolean primaryActivated,
                                     List<String> addedFingerprints) {

    public ApiKeyBatchSaveOutcome {
        addedFingerprints = addedFingerprints == null ? List.of() : List.copyOf(addedFingerprints);
    }

    public static ApiKeyBatchSaveOutcome of(int added, int alreadyExists, boolean primaryActivated,
                                            List<String> addedFingerprints) {
        return new ApiKeyBatchSaveOutcome(added, alreadyExists, primaryActivated, addedFingerprints);
    }

    /** 验收口径文案：「新增 N 把 / 已存在 M 把」。 */
    public String message() {
        return "新增 " + added + " 把 / 已存在 " + alreadyExists + " 把";
    }
}
