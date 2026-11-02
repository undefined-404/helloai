package com.helloai.core.system.quota;

import java.util.Optional;

/**
 * 余额查询密钥来源抽象。
 *
 * <p>「key 在哪」是**部署形态问题**，不是协议问题，因此由本层按 provider 路由，
 * provider 自身不关心 key 来自 vault 还是 sys_config。</p>
 */
public interface BalanceCredentialResolver {

    /** 返回可直接放进 Authorization 头的明文 key；无凭据返回 {@link Optional#empty()}。 */
    Optional<String> resolve(String providerKey);
}