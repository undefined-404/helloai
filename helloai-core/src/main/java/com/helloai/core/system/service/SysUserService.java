package com.helloai.core.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysUser;

/**
 * 系统用户服务接口。
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 创建管理员用户
     */
    SysUser create(String username, String password, String nickname, String role);

    /**
     * 更新最后登录信息
     */
    void updateLoginInfo(Long userId, String ip);

    /**
     * 更新用户信息
     */
    void updateUser(Long userId, String nickname, String email, String phone, String status);

    /**
     * 重置密码
     */
    void resetPassword(Long userId, String newPassword);

    /**
     * 管理员修改自己的密码
     */
    void changePassword(Long userId, String currentPassword, String newPassword);
}
