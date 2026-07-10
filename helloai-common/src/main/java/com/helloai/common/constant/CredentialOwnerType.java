package com.helloai.common.constant;

/**
 * 凭证归属对象类型。
 *
 * <p>当前阶段仅落地到 Agent 维度，保留枚举是为了后续向平台级 / 用户级 / 组织级扩展时
 * 不必再修改表结构字段语义。</p>
 */
public enum CredentialOwnerType {

    /** Agent 级凭证，当前 `credential_vault` 的唯一落地场景。 */
    AGENT
}
