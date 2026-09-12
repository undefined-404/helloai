package com.helloai.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.helloai.api.dto.system.DataRuleSaveRequest;
import com.helloai.api.dto.system.DataRuleVO;
import com.helloai.api.dto.system.MenuTreeNodeVO;
import com.helloai.api.dto.system.MenuTreeNodeVOs;
import com.helloai.api.dto.system.SysPermissionSaveRequest;
import com.helloai.common.base.R;
import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysPermissionDataRule;
import com.helloai.core.system.service.SysPermissionDataRuleService;
import com.helloai.core.system.service.SysPermissionQueryService;
import com.helloai.core.system.service.SysPermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 权限码 / 菜单管理接口（RBAC，BASE-2.1 / BASE-3.3）。
 *
 * <p>GET 列表供「角色授权」勾选 + 「菜单/权限管理」页展示；/tree 返回全量权限树
 * （MENU 挂层级 + API 平铺）。写操作按动作级权限码鉴权（permission:add / edit / delete）。
 * 数据规则端点（BASE-3.3）配置权限码的行级数据可见范围（受控枚举）。
 * 位于 {@code /api/admin/**} 下，由 AdminOnlyInterceptor 强制 admin 身份。</p>
 */
@RestController
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
public class SysPermissionController {

    private final SysPermissionQueryService sysPermissionQueryService;
    private final SysPermissionService sysPermissionService;
    private final SysPermissionDataRuleService sysPermissionDataRuleService;

    /** 权限码全量列表（平铺，角色授权勾选用） */
    @SaCheckPermission("permission:view")
    @GetMapping
    public R<List<MenuTreeNodeVO>> list() {
        return R.ok(MenuTreeNodeVOs.fromList(sysPermissionQueryService.listAll()));
    }

    /** 全量权限树（管理页展示：MENU 挂层级，API 平铺叶子） */
    @SaCheckPermission("permission:view")
    @GetMapping("/tree")
    public R<List<MenuTreeNodeVO>> tree() {
        return R.ok(MenuTreeNodeVOs.fromList(sysPermissionQueryService.listPermissionTree()));
    }

    @SaCheckPermission("permission:add")
    @PostMapping
    public R<MenuTreeNodeVO> create(@Valid @RequestBody SysPermissionSaveRequest req) {
        return R.ok(MenuTreeNodeVOs.from(sysPermissionService.createPermission(toEntity(req))));
    }

    @SaCheckPermission("permission:edit")
    @PutMapping("/{id}")
    public R<MenuTreeNodeVO> update(@PathVariable("id") Long id,
                                    @RequestBody SysPermissionSaveRequest req) {
        return R.ok(MenuTreeNodeVOs.from(sysPermissionService.updatePermission(id, toEntity(req))));
    }

    @SaCheckPermission("permission:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        sysPermissionService.deletePermission(id);
        return R.ok();
    }

    /** 查询权限码数据规则（BASE-3.3；未配置返回 ALL 默认） */
    @SaCheckPermission("permission:view")
    @GetMapping("/{id}/data-rule")
    public R<DataRuleVO> dataRule(@PathVariable("id") Long id) {
        SysPermissionDataRule rule = sysPermissionDataRuleService.getByPermissionId(id);
        if (rule == null) {
            return R.ok(DataRuleVO.of(id, "ALL", null));
        }
        return R.ok(DataRuleVO.of(id, rule.getRuleType(), rule.getRuleValue()));
    }

    /** 保存权限码数据规则（BASE-3.3，受控枚举） */
    @SaCheckPermission("permission:edit")
    @PutMapping("/{id}/data-rule")
    public R<Void> saveDataRule(@PathVariable("id") Long id,
                                @Valid @RequestBody DataRuleSaveRequest req) {
        sysPermissionDataRuleService.saveRule(id, req.getRuleType(), req.getRuleValue());
        return R.ok();
    }

    private SysPermission toEntity(SysPermissionSaveRequest req) {
        SysPermission p = new SysPermission();
        p.setCode(req.getCode());
        p.setName(req.getName());
        p.setType(req.getType());
        p.setSort(req.getSort());
        p.setParentId(req.getParentId());
        p.setPath(req.getPath());
        p.setIcon(req.getIcon());
        p.setComponent(req.getComponent());
        p.setHidden(req.getHidden());
        p.setKeepAlive(req.getKeepAlive());
        p.setExternalLink(req.getExternalLink());
        return p;
    }
}
