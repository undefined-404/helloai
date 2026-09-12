package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.SysUserStatus;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.entity.SysUserDepart;
import com.helloai.core.system.mapper.SysUserDepartMapper;
import com.helloai.core.system.mapper.SysUserMapper;
import com.helloai.core.system.service.AuthService;
import com.helloai.core.system.service.SysPermissionDataRuleService;
import com.helloai.core.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final AuthService authService;
    private final SysUserDepartMapper sysUserDepartMapper;
    private final SysPermissionDataRuleService sysPermissionDataRuleService;

    /**
     * 创建管理员用户
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
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
        user.setStatus(SysUserStatus.ACTIVE.name());
        save(user);

        log.info("管理员用户创建: username={}, role={}", username, user.getRole());
        return user;
    }

    /**
     * 更新最后登录信息
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateLoginInfo(Long userId, String ip) {
        try {
            int updated = baseMapper.update(null,
                    Wrappers.<SysUser>lambdaUpdate()
                            .eq(SysUser::getId, userId)
                            .set(SysUser::getLastLoginTime, OffsetDateTime.now())
                            .set(SysUser::getLastLoginIp, ip)
            );
            log.info("管理员最后登录信息已更新: userId={}, ip={}, rows={}", userId, ip, updated);
        } catch (Exception e) {
            log.error("更新管理员最后登录信息失败: userId={}, ip={}", userId, ip, e);
            throw new BizException("记录最后登录信息失败: " + e.getMessage());
        }
    }

    /**
     * 更新用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
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
    @Override
    public void resetPassword(Long userId, String newPassword) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BizException("用户不存在: " + userId);
        }
        user.setPassword(authService.encodePassword(newPassword));
        updateById(user);
        log.info("管理员密码重置: id={}", userId);
    }

    /**
     * 管理员修改自己的密码
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new BizException("用户不存在: " + userId);
        }
        if (!authService.matchesPassword(currentPassword, user.getPassword())) {
            throw new BizException("当前密码不正确");
        }
        if (currentPassword.equals(newPassword)) {
            throw new BizException("新密码不能与当前密码相同");
        }
        user.setPassword(authService.encodePassword(newPassword));
        updateById(user);
        log.info("管理员修改密码: id={}", userId);
    }

    @Override
    public IPage<SysUser> pageUsers(String keyword, long page, long size, Long departId, Long currentUserId) {
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        // 数据权限（BASE-3.3）：按 user:view 规则解析可见用户范围；null=不限制
        Set<Long> visibleUserIds = sysPermissionDataRuleService.resolveVisibleUserIds(currentUserId, "user:view");
        List<Long> userIdsInDepart = null;
        if (departId != null) {
            // 部门筛选：先取该部门下用户 id（走 sys_user_depart 索引），再主表分页
            userIdsInDepart = sysUserDepartMapper.selectList(Wrappers.<SysUserDepart>lambdaQuery()
                            .eq(SysUserDepart::getDepartId, departId))
                    .stream()
                    .map(SysUserDepart::getUserId)
                    .distinct()
                    .toList();
            if (userIdsInDepart.isEmpty()) {
                return new Page<>(page, size);
            }
        }
        // 数据权限 + 部门筛选取交集
        List<Long> idFilter = null;
        if (visibleUserIds != null && userIdsInDepart != null) {
            idFilter = userIdsInDepart.stream().filter(visibleUserIds::contains).toList();
        } else if (visibleUserIds != null) {
            idFilter = new ArrayList<>(visibleUserIds);
        } else {
            idFilter = userIdsInDepart;
        }
        if (idFilter != null && idFilter.isEmpty()) {
            return new Page<>(page, size);
        }
        return page(new Page<>(page, size), Wrappers.<SysUser>lambdaQuery()
                .and(hasKeyword, w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getNickname, keyword))
                .in(idFilter != null, SysUser::getId, idFilter)
                .orderByDesc(SysUser::getCreateTime));
    }
}
