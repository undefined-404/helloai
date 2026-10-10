package com.helloai.api.support;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.core.system.entity.SysUser;
import com.helloai.core.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 当前管理端操作人解析（管理端审计/台账共用）。
 *
 * <p><b>为什么要它</b>：Sa-Token 的会话里**只存了 userId**（{@code StpUtil.login(user.getId())}），
 * 显示名要回查 {@code sys_user}。而需要"谁干的"的场景不止一处（技能包安装审计、平台备份台账），
 * 各写一遍会漂移；且 {@code MyBatisPlusMetaObjectHandler#getCurrentUser()} 是硬编码桩、恒返
 * {@code "system"}（差距表 §7.1.3 R5），**不能**依赖自动填充。</p>
 *
 * <p><b>取不到不阻断</b>：审计/台账只丢显示名，业务照常 —— 审计不得反向卡业务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminOperatorResolver {

    /** 操作人标识：id 与显示名（都取不到时为空串）。 */
    public record Operator(String id, String name) {

        public static Operator unknown() {
            return new Operator("", "");
        }
    }

    private final SysUserService sysUserService;

    public Operator current() {
        try {
            Long userId = Long.valueOf(StpUtil.getLoginId().toString());
            SysUser user = sysUserService.getById(userId);
            String displayName = "";
            if (user != null) {
                displayName = (user.getNickname() != null && !user.getNickname().isBlank())
                        ? user.getNickname() : user.getUsername();
            }
            return new Operator(String.valueOf(userId), displayName);
        } catch (Exception e) {
            log.warn("取当前操作人失败，本条记录只记动作不记人: {}", e.getMessage());
            return Operator.unknown();
        }
    }
}
