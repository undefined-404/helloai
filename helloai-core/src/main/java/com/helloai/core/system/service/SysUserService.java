package com.helloai.core.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.SysUser;

/**
 * 系统用户服务接口。
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 创建系统用户（建号即签发角色）。
     *
     * <p>身份单事实源为 {@code sys_user_role}（BASE-4.2 起 {@code sys_user.role} 已退场）：
     * 用户落库后立即按 {@code role} 解析角色码 → {@code sys_role.id} 并写入关联表，
     * 二者同一事务，避免出现「建了号却无角色」的空权限账号。</p>
     *
     * @param role   角色码（如 SUPER_ADMIN / ADMIN）；为空时取默认 ADMIN
     * @param remark 备注（可空）
     */
    SysUser create(String username, String password, String nickname, String role, String remark);

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

    /**
     * 用户分页（BASE-3.2 支持按部门筛选；BASE-3.3 应用数据权限）。
     *
     * @param keyword       username / nickname 模糊匹配，空则不过滤
     * @param departId      部门 ID，非空时仅返回该部门下用户；空则不过滤
     * @param currentUserId 当前登录用户 ID，用于按 user:view 数据规则过滤可见范围；
     *                      空则不应用数据权限
     */
    IPage<SysUser> pageUsers(String keyword, long page, long size, Long departId, Long currentUserId);
}
