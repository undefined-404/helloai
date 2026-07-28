package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 统一鉴权服务。
 * 负责管理员登录（DB 查 sys_user + BCrypt 校验）、管理员会话维护与 Agent API Key 验证。
 *
 * <p><b>会话存储</b>：管理员会话存 Redis（key 前缀 {@link #ADMIN_TOKEN_KEY_PREFIX}，
 * TTL {@link #ADMIN_TOKEN_TTL}，每次校验命中后滑动续期），后端重启不再导致会话丢失。
 * Redis 为鉴权强依赖（与心跳/MQ 幂等一致），不做内存降级。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Redis 缓存键前缀 + token（对齐 agent:heartbeat: / mq:dedup: 命名风格）。 */
    public static final String ADMIN_TOKEN_KEY_PREFIX = "auth:admin:token:";
    /** 管理员会话 TTL = 8 小时，每次校验命中后滑动续期。 */
    public static final Duration ADMIN_TOKEN_TTL = Duration.ofHours(8);

    private final SysUserMapper sysUserMapper;
    private final AgentMapper agentMapper;
    private final StringRedisTemplate redis;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 会话 JSON 序列化专用（不复用全局 Bean，避免 Long→String 等前端定制序列化策略干扰内部存储）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 管理员登录
     */
    public AdminSession adminLogin(String username, String rawPassword) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getUsername, username)
                        .eq(SysUser::getStatus, "ACTIVE")
        );
        if (user == null) {
            throw new BizException("用户不存在或已禁用");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BizException("密码错误");
        }

        String token = generateToken();
        AdminSession session = new AdminSession(token, user.getId(), user.getUsername(), user.getNickname(), user.getRole());
        redis.opsForValue().set(ADMIN_TOKEN_KEY_PREFIX + token, toJson(session), ADMIN_TOKEN_TTL);

        log.info("管理员登录成功: username={}, id={}", username, user.getId());
        return session;
    }

    /**
     * 验证管理员 token（Redis 命中后滑动续期）
     *
     * @throws BizException 401 当 token 不存在、已过期或缓存值损坏时
     */
    public AdminSession validateAdminToken(String token) {
        String key = ADMIN_TOKEN_KEY_PREFIX + token;
        String json = redis.opsForValue().get(key);
        if (json == null) {
            throw new BizException(401, "管理员登录已过期，请重新登录");
        }
        AdminSession session;
        try {
            session = objectMapper.readValue(json, AdminSession.class);
        } catch (Exception e) {
            // 缓存值损坏（序列化格式变更/脏数据）：清掉该 key，强制重新登录
            log.warn("管理员会话缓存反序列化失败，已清理: key={}", key, e);
            redis.delete(key);
            throw new BizException(401, "管理员登录已过期，请重新登录");
        }
        // 滑动续期：活跃会话不会在使用中途过期
        redis.expire(key, ADMIN_TOKEN_TTL);
        return session;
    }

    /**
     * 管理员登出
     */
    public void adminLogout(String token) {
        redis.delete(ADMIN_TOKEN_KEY_PREFIX + token);
        log.info("管理员登出");
    }

    /**
     * Agent API Key 验证
     */
    public Agent validateAgentKey(String apiKey) {
        Agent agent = agentMapper.selectOne(
                Wrappers.<Agent>lambdaQuery()
                        .eq(Agent::getApiKey, apiKey)
        );
        if (agent == null) {
            throw new BizException(401, "无效的 API Key");
        }
        if (agent.getStatus() == AgentStatus.DISABLED) {
            throw new BizException(403, "Agent 已禁用");
        }
        return agent;
    }

    /**
     * 加密明文密码
     */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验明文密码是否匹配加密密码
     */
    public boolean matchesPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    /**
     * 管理员会话信息
     */
    public record AdminSession(String token, Long id, String username, String displayName, String role) {}

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String toJson(AdminSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            // 序列化异常属于编码错误，按规范 §12.3 包装为 RuntimeException
            throw new RuntimeException("管理员会话序列化失败", e);
        }
    }
}
