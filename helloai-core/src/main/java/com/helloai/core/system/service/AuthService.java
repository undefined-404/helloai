package com.helloai.core.system.service;

import com.helloai.core.agent.entity.Agent;

import java.time.Duration;

/**
 * 统一鉴权服务。
 * 负责管理员登录（DB 查 sys_user + BCrypt 校验）、管理员会话维护与 Agent API Key 验证。
 *
 * <p><b>会话存储</b>：管理员会话存 Redis（key 前缀 {@link #ADMIN_TOKEN_KEY_PREFIX}，
 * TTL {@link #ADMIN_TOKEN_TTL}，每次校验命中后滑动续期），后端重启不再导致会话丢失。
 * Redis 为鉴权强依赖（与心跳/MQ 幂等一致），不做内存降级。</p>
 */
public interface AuthService {

    /** Redis 缓存键前缀 + token（对齐 agent:heartbeat: / mq:dedup: 命名风格）。 */
    String ADMIN_TOKEN_KEY_PREFIX = "auth:admin:token:";
    /** 管理员会话 TTL = 8 小时，每次校验命中后滑动续期。 */
    Duration ADMIN_TOKEN_TTL = Duration.ofHours(8);

    /**
     * 管理员登录
     */
    AdminSession adminLogin(String username, String rawPassword);

    /**
     * 验证管理员 token（Redis 命中后滑动续期）
     *
     * @throws com.helloai.common.base.BizException 401 当 token 不存在、已过期或缓存值损坏时
     */
    AdminSession validateAdminToken(String token);

    /**
     * 管理员登出
     */
    void adminLogout(String token);

    /**
     * Agent API Key 验证
     */
    Agent validateAgentKey(String apiKey);

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
