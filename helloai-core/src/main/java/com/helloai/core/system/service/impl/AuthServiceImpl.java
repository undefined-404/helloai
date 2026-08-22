package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SysUserStatus;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.AuthService;
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
 * 统一鉴权服务实现。
 *
 * <p>Agent API Key 验证已按 §3.x 依赖方向红线下沉至 agent 域
 * {@code AgentAuthPort}（由 AgentServiceImpl 实现），本实现只保留管理员认证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final StringRedisTemplate redis;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 会话 JSON 序列化专用（不复用全局 Bean，避免 Long→String 等前端定制序列化策略干扰内部存储）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 管理员登录
     */
    @Override
    public AdminSession adminLogin(String username, String rawPassword) {
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getUsername, username)
                        .eq(SysUser::getStatus, SysUserStatus.ACTIVE.name())
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
    @Override
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
    @Override
    public void adminLogout(String token) {
        redis.delete(ADMIN_TOKEN_KEY_PREFIX + token);
        log.info("管理员登出");
    }

    /**
     * 加密明文密码
     */
    @Override
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验明文密码是否匹配加密密码
     */
    @Override
    public boolean matchesPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

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
