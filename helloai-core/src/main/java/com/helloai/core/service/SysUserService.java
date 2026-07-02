package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.entity.SysUser;
import com.helloai.core.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserService extends ServiceImpl<SysUserMapper, SysUser> {

    private final AuthService authService;

    /**
     * 创建管理员用户
     */
    @Transactional(rollbackFor = Exception.class)
    public SysUser create(String username, String password, String nickname, String role) {
        var existing = lambdaQuery().eq(SysUser::getUsername, username).one();
        if (existing != null) {
            throw new BizException("用户名 '" + username + "' 已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(authService.encodePassword(password));
        user.setNickname(nickname);
        user.setRole(role != null ? role : "ADMIN");
        user.setStatus("ACTIVE");
        save(user);

        log.info("管理员用户创建: username={}, role={}", username, user.getRole());
        return user;
    }

    /**
     * 更新最后登录信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateLoginInfo(Long userId, String ip) {
        lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getLastLoginTime, OffsetDateTime.now())
                .set(SysUser::getLastLoginIp, ip)
                .update();
    }

    /**
     * 更新用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(Long userId, String nickname, String email, String phone, String status) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BizException("用户不存在: " + userId);
        }
        if (nickname != null) user.setNickname(nickname);
        if (email != null) user.setEmail(email);
        if (phone != null) user.setPhone(phone);
        if (status != null) user.setStatus(status);
        updateById(user);
        log.info("管理员用户更新: id={}", userId);
    }

    /**
     * 重置密码
     */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BizException("用户不存在: " + userId);
        }
        user.setPassword(authService.encodePassword(newPassword));
        updateById(user);
        log.info("管理员密码重置: id={}", userId);
    }
}
