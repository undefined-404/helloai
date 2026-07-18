package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SysUser;
import com.helloai.core.mapper.AgentMapper;
import com.helloai.core.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final AgentMapper agentMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 管理员 session token 存储（内存，重启后失效需重新登录）
     */
    private final Map<String, AdminSession> adminTokens = new ConcurrentHashMap<>();

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
        adminTokens.put(token, session);

        log.info("管理员登录成功: username={}, id={}", username, user.getId());
        return session;
    }

    /**
     * 验证管理员 token
     */
    public AdminSession validateAdminToken(String token) {
        AdminSession session = adminTokens.get(token);
        if (session == null) {
            throw new BizException(401, "管理员登录已过期，请重新登录");
        }
        return session;
    }

    /**
     * 管理员登出
     */
    public void adminLogout(String token) {
        adminTokens.remove(token);
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
}
