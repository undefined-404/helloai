package com.helloai.core.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.system.entity.SysPermission;
import com.helloai.core.system.entity.SysRolePermission;
import com.helloai.core.system.mapper.SysPermissionMapper;
import com.helloai.core.system.mapper.SysRolePermissionMapper;
import com.helloai.core.system.service.SysPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 系统权限/菜单管理服务实现（RBAC 写侧，BASE-2.1）。
 *
 * <p>权限码同时作为「菜单树」与「接口鉴权」的唯一事实源，删除必须守卫
 * （有子节点 / 被角色引用拒绝），避免破坏菜单层级与授权绑定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermission>
        implements SysPermissionService {

    private static final String TYPE_MENU = "MENU";
    private static final String TYPE_API = "API";

    private final SysRolePermissionMapper sysRolePermissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysPermission createPermission(SysPermission req) {
        String code = normalizeCode(req.getCode());
        String type = normalizeType(req.getType());
        Long count = lambdaQuery().eq(SysPermission::getCode, code).count();
        if (count != null && count > 0) {
            throw new BizException("权限码 '" + code + "' 已存在");
        }
        validateParent(type, req.getParentId());
        int defaultHome = normalizeDefaultHome(req.getIsDefaultHome());
        if (defaultHome == 1) {
            validateDefaultHome(type, req.getPath(), req.getComponent(), req.getExternalLink());
            clearOtherDefaultHomes(null);
        }
        SysPermission p = new SysPermission();
        p.setCode(code);
        p.setName(req.getName());
        p.setType(type);
        p.setSort(req.getSort() != null ? req.getSort() : 0);
        if (TYPE_MENU.equals(type)) {
            p.setParentId(req.getParentId());
            p.setPath(trimToNull(req.getPath()));
            p.setIcon(trimToNull(req.getIcon()));
            p.setComponent(trimToNull(req.getComponent()));
            // 路由渲染增强（BASE-3.1）：默认显示、不缓存、无外链
            p.setHidden(req.getHidden() != null ? req.getHidden() : 0);
            p.setKeepAlive(req.getKeepAlive() != null ? req.getKeepAlive() : 0);
            p.setExternalLink(trimToNull(req.getExternalLink()));
            p.setIsDefaultHome(defaultHome);
        } else {
            p.setIsDefaultHome(0);
        }
        save(p);
        log.info("权限码创建: code={}, type={}, id={}", code, type, p.getId());
        return p;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysPermission updatePermission(Long id, SysPermission req) {
        SysPermission p = getById(id);
        if (p == null) {
            throw new BizException("权限码不存在: " + id);
        }
        String type = req.getType() != null ? normalizeType(req.getType()) : p.getType();
        Long parentId = req.getParentId() != null ? req.getParentId() : p.getParentId();
        validateParent(type, parentId);
        if (TYPE_API.equals(type) && normalizeDefaultHome(req.getIsDefaultHome()) == 1) {
            throw new BizException("仅菜单可设为默认首页");
        }
        if (req.getName() != null) p.setName(req.getName());
        p.setType(type);
        p.setSort(req.getSort() != null ? req.getSort() : p.getSort());
        if (TYPE_MENU.equals(type)) {
            p.setParentId(parentId);
            if (req.getPath() != null) p.setPath(trimToNull(req.getPath()));
            if (req.getIcon() != null) p.setIcon(trimToNull(req.getIcon()));
            if (req.getComponent() != null) p.setComponent(trimToNull(req.getComponent()));
            if (req.getHidden() != null) p.setHidden(req.getHidden());
            if (req.getKeepAlive() != null) p.setKeepAlive(req.getKeepAlive());
            if (req.getExternalLink() != null) p.setExternalLink(trimToNull(req.getExternalLink()));
            if (req.getIsDefaultHome() != null) {
                int defaultHome = normalizeDefaultHome(req.getIsDefaultHome());
                if (defaultHome == 1) {
                    validateDefaultHome(type, p.getPath(), p.getComponent(), p.getExternalLink());
                    clearOtherDefaultHomes(id);
                }
                p.setIsDefaultHome(defaultHome);
            } else if (normalizeDefaultHome(p.getIsDefaultHome()) == 1) {
                // 已为默认首页时修改路径/组件/外链，须保证仍可跳转
                validateDefaultHome(type, p.getPath(), p.getComponent(), p.getExternalLink());
            }
        } else {
            // API 类型无层级/渲染字段
            p.setParentId(null);
            p.setPath(null);
            p.setIcon(null);
            p.setComponent(null);
            p.setHidden(0);
            p.setKeepAlive(0);
            p.setExternalLink(null);
            p.setIsDefaultHome(0);
        }
        updateById(p);
        log.info("权限码更新: id={}, code={}, type={}", id, p.getCode(), type);
        return p;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePermission(Long id) {
        SysPermission p = getById(id);
        if (p == null) {
            return;
        }
        if (TYPE_MENU.equals(p.getType())) {
            Long childCount = baseMapper.selectCount(Wrappers.<SysPermission>lambdaQuery()
                    .eq(SysPermission::getParentId, id));
            if (childCount != null && childCount > 0) {
                throw new BizException("存在子菜单，请先删除子菜单");
            }
        }
        Long roleRef = sysRolePermissionMapper.selectCount(Wrappers.<SysRolePermission>lambdaQuery()
                .eq(SysRolePermission::getPermissionId, id));
        if (roleRef != null && roleRef > 0) {
            throw new BizException("该权限已被角色绑定引用，请先解除绑定");
        }
        removeById(id);
        log.info("权限码删除: id={}, code={}, type={}", id, p.getCode(), p.getType());
    }

    /** code 统一大写并 trim（菜单/权限码唯一标识不区分大小写） */
    private String normalizeCode(String code) {
        String c = trimToNull(code);
        if (c == null) {
            throw new BizException("权限码不能为空");
        }
        return c.toUpperCase(Locale.ROOT);
    }

    private String normalizeType(String type) {
        String t = trimToNull(type);
        if (t == null) {
            throw new BizException("权限类型不能为空");
        }
        String up = t.toUpperCase(Locale.ROOT);
        if (!TYPE_MENU.equals(up) && !TYPE_API.equals(up)) {
            throw new BizException("权限类型仅支持 MENU / API");
        }
        return up;
    }

    /** MENU 带父节点时校验父存在且为 MENU；API 不允许挂父 */
    private void validateParent(String type, Long parentId) {
        if (TYPE_API.equals(type)) {
            return;
        }
        if (parentId == null) {
            return;
        }
        SysPermission parent = getById(parentId);
        if (parent == null) {
            throw new BizException("父菜单不存在: " + parentId);
        }
        if (!TYPE_MENU.equals(parent.getType())) {
            throw new BizException("父节点必须是菜单类型（MENU）");
        }
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 默认首页开关归一：仅 1 视为开启，其余（含 null）一律 0。 */
    private int normalizeDefaultHome(Integer value) {
        return value != null && value == 1 ? 1 : 0;
    }

    /**
     * 默认首页必须是可跳转的菜单页面：有路由、无查询参数、有组件、非外链。
     * 分组（component 为空）与外链不能作为登录落点。
     */
    private void validateDefaultHome(String type, String path, String component, String externalLink) {
        if (!TYPE_MENU.equals(type)) {
            throw new BizException("仅菜单可设为默认首页");
        }
        String p = trimToNull(path);
        if (p == null || p.contains("?")) {
            throw new BizException("默认首页必须配置可跳转的路由路径（不含查询参数）");
        }
        if (trimToNull(component) == null) {
            throw new BizException("默认首页必须是可跳转页面（组件路径不能为空）");
        }
        if (trimToNull(externalLink) != null) {
            throw new BizException("外链菜单不能设为默认首页");
        }
    }

    /** 全平台只保留一个默认首页；keepId 非空时保留自身。 */
    private void clearOtherDefaultHomes(Long keepId) {
        var update = lambdaUpdate()
                .eq(SysPermission::getIsDefaultHome, 1)
                .set(SysPermission::getIsDefaultHome, 0);
        if (keepId != null) {
            update.ne(SysPermission::getId, keepId);
        }
        update.update();
    }
}
