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
 * <p>返回当前登录管理员可见的菜单树（type=MENU，按权限码过滤 + parent 挂接），
 * 前端侧边栏 + 动态路由按此渲染，不再硬编码菜单结构。位于 {@code /api/admin/**} 下，
 * 由 AdminOnlyInterceptor 强制 admin 身份；细粒度过滤在服务层按权限码完成。</p>
 */
@RestController
@RequestMapping("/api/admin/menus")
@RequiredArgsConstructor
public class MenuController {

    private final SysPermissionQueryService sysPermissionQueryService;

    @GetMapping("/tree")
    public R<List<MenuTreeNodeVO>> tree() {
        Long userId = Long.valueOf(StpUtil.getLoginId().toString());
        return R.ok(MenuTreeNodeVOs.fromList(sysPermissionQueryService.listMenuTree(userId)));
    }
}
