package com.helloai.core.system.service;

/**
 * 统一鉴权服务。
 * 负责管理员登录（DB 查 sys_user + BCrypt 校验）、管理员会话维护与 Agent API Key 验证。
 *
 * <p><b>会话存储</b>：管理员会话由 Sa-Token 承担（登录态存 Redis，键前缀取自
 * {@code sa-token.token-name}，本项目为 {@code X-Admin-Token:}，非库默认 {@code satoken:}；
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
     * 认证 admin 请求（Sa-Token 标准守门）。
     *
     * <p>走 Sa-Token 标准链路 {@code StpUtil.checkLogin()}——会触发 active-timeout 校验与
     * 滑动续期（{@code updateLastActiveToNow}），因此是本项目 admin 认证的**守门入口**。
     * Sa-Token 未命中时回退存量自建会话迁移（以原 token 值重建），仍失败则抛 401。</p>
     *
     * @throws com.helloai.common.base.BizException 401 当会话不存在、已过期或对应用户失效时
     */
    AdminSession authenticateAdmin(String token);

    /**
     * 按 token 回读管理员会话信息（不触发校验与续期）。
     *
     * <p>供已知 token 的回读场景使用（{@code /api/auth/me}）；请求守门请用
     * {@link #authenticateAdmin(String)}。</p>
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
     * 管理员会话信息。
     *
     * <p>不含角色字段：角色是 {@code sys_user_role} 的多对多事实源（BASE-4.2 起
     * {@code sys_user.role} 单字段已退场），角色/权限码由登录响应单独下发
     * （{@code permissions} / {@code roles}），不随会话快照走。</p>
     */
    record AdminSession(String token, Long id, String username, String displayName) {}
}
