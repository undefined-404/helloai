package com.helloai.core.system.service;

/**
 * 统一鉴权服务。
 * 负责管理员登录（DB 查 sys_user + BCrypt 校验）、管理员会话维护与 Agent API Key 验证。
 *
 * <p><b>会话存储</b>：管理员会话由 Sa-Token 承担（登录态存 Redis {@code satoken:} 前缀，
 * token 从 {@code X-Admin-Token} 头读取，见 application.yml sa-token 配置；滑动续期
 * active-timeout=28800s），后端重启不再导致会话丢失。</p>
 *
 * <p><b>Agent API Key 验证</b>：按 §3.x 依赖方向红线下沉至 agent 域
 * {@code AgentAuthPort}（由 AgentServiceImpl 实现），本服务不再依赖 agent 域。</p>
 */
public interface AuthService {

    /**
     * 管理员登录
     */
    AdminSession adminLogin(String username, String rawPassword);

    /**
     * 验证管理员 token（Sa-Token 会话校验 + 回读用户信息）
     *
     * @throws com.helloai.common.base.BizException 401 当 token 不存在、已过期或对应用户失效时
     */
    AdminSession validateAdminToken(String token);

    /**
     * 管理员登出
     */
    void adminLogout(String token);

    /**
     * 加密明文密码
     */
    String encodePassword(String rawPassword);

    /**
     * 校验明文密码是否匹配加密密码
     */
    boolean matchesPassword(String rawPassword, String encodedPassword);

    /**
     * 管理员会话信息
     */
    record AdminSession(String token, Long id, String username, String displayName, String role) {}
}
