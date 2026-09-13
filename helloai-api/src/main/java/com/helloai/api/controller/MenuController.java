package com.helloai.api.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.helloai.api.dto.system.MenuTreeNodeVO;
import com.helloai.api.dto.system.MenuTreeNodeVOs;
import com.helloai.common.base.R;
import com.helloai.core.system.service.SysPermissionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单树接口（RBAC 菜单 DB 化读侧）。
 *
 * <p>返回<strong>当前登录账号自身</strong>可见的菜单树（type=MENU，按权限码过滤 + parent 挂接），
 * 前端侧边栏 + 动态路由按此渲染，不再硬编码菜单结构。</p>
 *
 * <p><b>路径归属</b>：BASE-4.3 / 4.4 起为 {@code /api/menus/tree}——「我的可见菜单」属自身资源，
 * 而非管理面操作。此前置于 {@code /api/admin/menus/tree}，会被 AdminOnlyInterceptor 的
 * 管理角色闸（SUPER_ADMIN|ADMIN）拦下，导致 NORMAL_USER / GUEST 拿不到菜单树、侧边栏为空。
 * 移出 {@code /api/admin/**} 后仅需登录（由 AuthInterceptor 认证），细粒度过滤仍由服务层按
 * 当前账号的权限码完成，不违反 CODE_STYLE §43「{@code /api/admin/**} 必须经 Admin 授权」。</p>
 */
@RestController
@RequestMapping("/api/menus")
@RequiredArgsConstructor
public class MenuController {

    private final SysPermissionQueryService sysPermissionQueryService;

    @GetMapping("/tree")
    public R<List<MenuTreeNodeVO>> tree() {
        Long userId = Long.valueOf(StpUtil.getLoginId().toString());
        return R.ok(MenuTreeNodeVOs.fromList(sysPermissionQueryService.listMenuTree(userId)));
    }
}
