package com.helloai.core.agent.dto;

/**
 * 平台级 API Key 保存结果（`M-6`）。
 *
 * <p>用于把「本次是新增还是命中已有 Key」这一事实从服务层传递给调用方（管理端接口），
 * 使「同一 Key 二次录入」可被识别并提示，而不是静默再落一行。</p>
 *
 * @param created     是否**本次新增**（{@code false} = 同 provider 下同指纹凭证已存在，未重复落库）
 * @param fingerprint 明文指纹（SHA-256 前缀十六进制串，24 字符）；**不可逆，不暴露明文**
 * @param message     面向调用方的可读结论
 */
public record ApiKeySaveOutcome(boolean created, String fingerprint, String message) {

    /** 可读结论：本次新增。 */
    public static final String MSG_CREATED = "API Key 已保存";

    /** 可读结论：命中已有 Key（同 provider 内同指纹）。 */
    public static final String MSG_ALREADY_EXISTS = "该 API Key 已存在，未重复保存";

    public static ApiKeySaveOutcome ofCreated(String fingerprint) {
        return new ApiKeySaveOutcome(true, fingerprint, MSG_CREATED);
    }

    public static ApiKeySaveOutcome ofAlreadyExists(String fingerprint) {
        return new ApiKeySaveOutcome(false, fingerprint, MSG_ALREADY_EXISTS);
    }
}
