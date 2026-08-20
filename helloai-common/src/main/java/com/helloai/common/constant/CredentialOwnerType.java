package com.helloai.common.constant;

/**
 * 凭证归属对象类型。
 *
 * <p>当前阶段落地到 Agent 与平台级两个维度，保留枚举是为了后续向用户级 / 组织级扩展时
 * 不必再修改表结构字段语义。</p>
 */
public enum CredentialOwnerType {

    /** Agent 级凭证，owner_id = agent.id。 */
    AGENT,

    /** 平台级凭证，owner_id 固定占位 0，按 provider 唯一（配合 迁移放开 CHECK 约束）。 */
    PLATFORM
}
