package com.helloai.core.system.port;

import java.util.Optional;

/**
 * 联网搜索 API Key 读取端口（G-026 端口反转配套，CODE_STYLE §7.2 情形①）。
 *
 * <p>博查余额查询在 system 域，Key 与联网搜索共用
 * {@code sys_config["web-search.bocha.api-key"]}（加密存储），读取与解密属 planner 域
 * {@code WebSearchCredentialKeyStore}。若 system 直接 import planner.search，即构成
 * {@code system → planner} 反向依赖（CODE_STYLE §6 禁止）。</p>
 *
 * <p>故端口定义在消费方 system，由提供方 planner 的 {@code WebSearchApiKeyPortAdapter}
 * 实现：{@code planner → system} 是顺向依赖。</p>
 */
public interface WebSearchApiKeyPort {

    /** 返回博查搜索 Key 明文；未配置或为空串返回 {@link Optional#empty()}。 */
    Optional<String> resolveBochaApiKey();
}
