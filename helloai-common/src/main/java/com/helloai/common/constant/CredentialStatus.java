package com.helloai.common.constant;

/**
 * 凭证状态。
 */
public enum CredentialStatus {

    /** 可被平台执行链路使用。 */
    ACTIVE,

    /** 已停用，保留记录但不再参与路由。 */
    DISABLED,

    /**
     * 已过期（轮换语义）。
     *
     * <p>AgentHub 轮换时旧凭证标为 EXPIRED 而非 DISABLED，
     * 区分"人为停用"和"自动轮换淘汰"，保留审计线索。</p>
     */
    EXPIRED
}
