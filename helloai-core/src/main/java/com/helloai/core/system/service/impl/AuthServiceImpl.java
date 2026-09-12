package com.helloai.core.system.service.impl;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * 统一鉴权服务实现。
 *
 * <p>管理员会话自 Sa-Token 承担（登录态存 Redis {@code satoken:} 前缀，替换旧自建
 * {@code auth:admin:token:} 会话）；Agent API Key 验证已按 §3.x 依赖方向红线下沉至 agent 域
 * {@code AgentAuthPort}（由 AgentServiceImpl 实现），本实现只保留管理员认证。</p>
 *
 * <p><b>存量会话无缝迁移</b>：切换前已登录的浏览器携带旧自建 token，Sa-Token 侧必然未登录。
 * {@link #validateAdminToken} 在 Sa-Token 未命中时回退查旧 Redis key（{@code auth:admin:token:}），
 * 命中则用 {@code SaLoginModel.setToken(旧token)} 以<strong>原 token 值</strong>重建 Sa-Token 会话
 * 并删除旧 key——前端无需感知 token 变化，后续请求自动走新会话。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** 旧自建会话 Redis key 前缀（AuthService 旧接口常量，迁移兜底读取用）。 */
    private static final String LEGACY_ADMIN_TOKEN_PREFIX = "auth:admin:token:";

    private final SysUserMapper sysUserMapper;
    private final StringRedisTemplate redis;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 旧会话 JSON 解析专用（不复用全局 Bean，避免 Long→String 等前端定制序列化策略干扰内部存储）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 管理员登录：DB 查 sys_user + BCrypt 校验，成功后 Sa-Token 建会话。
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

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        AdminSession session = new AdminSession(token, user.getId(), user.getUsername(), user.getNickname(), user.getRole());

        log.info("管理员登录成功: username={}, id={}", username, user.getId());
        return session;
    }

    /**
     * 验证管理员 token：Sa-Token 校验并反查 loginId，再回读用户信息。
     * Sa-Token 未命中时回退旧自建 Redis 会话做无缝迁移（保留原 token 值重建会话）。
     *
     * @throws BizException 401 当 token 不存在、已过期或对应用户失效时
     */
    @Override
    public AdminSession validateAdminToken(String token) {
        Long userId;
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            // Sa-Token 的 getLoginIdByToken 对未命中 token 返回 null（不抛异常），
            // 此时回退旧自建 Redis 会话做无缝迁移（保留原 token 值重建会话）
            userId = migrateLegacySession(token);
            if (userId == null) {
                throw new BizException(401, "管理员登录已过期，请重新登录");
            }
        } else {
            userId = Long.valueOf(loginId.toString());
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !SysUserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new BizException(401, "管理员登录已过期，请重新登录");
        }
        return new AdminSession(token, user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    /**
     * 存量会话迁移：读取旧自建 Redis 会话（{@code auth:admin:token:{token}} → JSON 含 id），
     * 命中则以原 token 值重建 Sa-Token 会话并删除旧 key。
     *
     * @return 迁移后的用户 id；旧会话不存在 / 解析失败返回 null
     */
    private Long migrateLegacySession(String token) {
        String key = LEGACY_ADMIN_TOKEN_PREFIX + token;
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.get("id") == null || node.get("id").isNull()) {
                log.warn("旧管理员会话缺少 id 字段，无法迁移，已清理: key={}", key);
                redis.delete(key);
                return null;
            }
            Long userId = node.get("id").asLong();
            // 以原 token 值重建 Sa-Token 会话：前端无需感知 token 变化，无缝切换
            StpUtil.login(userId, new SaLoginModel().setToken(token));
            redis.delete(key);
            log.info("存量管理员会话已迁移到 Sa-Token: userId={}", userId);
            return userId;
        } catch (Exception e) {
            // 缓存值损坏（序列化格式变更/脏数据）：清掉该 key，强制重新登录
            log.warn("旧管理员会话反序列化失败，已清理: key={}", key);
            redis.delete(key);
            return null;
        }
    }

    /**
     * 管理员登出：按 token 值销毁 Sa-Token 会话。
     */
    @Override
    public void adminLogout(String token) {
        StpUtil.logoutByTokenValue(token);
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
}
