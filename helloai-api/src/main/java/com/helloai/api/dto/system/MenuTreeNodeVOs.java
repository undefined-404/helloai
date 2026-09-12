package com.helloai.api.dto.system;

import com.helloai.core.system.entity.SysPermission;

import java.util.List;

/**
 * SysPermission → MenuTreeNodeVO 投影工具（API 层不直接暴露 DB 实体）。
 *
 * <p>两个消费点共用：菜单树接口（按用户过滤）+ 权限管理接口（全量树/列表）；
 * 递归映射 children，避免各 Controller 重复实现。</p>
 */
public final class MenuTreeNodeVOs {

    private MenuTreeNodeVOs() {
    }

    public static MenuTreeNodeVO from(SysPermission p) {
        MenuTreeNodeVO vo = new MenuTreeNodeVO();
        vo.setId(p.getId());
        vo.setCode(p.getCode());
        vo.setName(p.getName());
        vo.setType(p.getType());
        vo.setSort(p.getSort());
        vo.setParentId(p.getParentId());
        vo.setPath(p.getPath());
        vo.setIcon(p.getIcon());
        vo.setComponent(p.getComponent());
        vo.setHidden(p.getHidden());
        vo.setKeepAlive(p.getKeepAlive());
        vo.setExternalLink(p.getExternalLink());
        if (p.getChildren() != null && !p.getChildren().isEmpty()) {
            vo.setChildren(p.getChildren().stream().map(MenuTreeNodeVOs::from).toList());
        }
        return vo;
    }

    public static List<MenuTreeNodeVO> fromList(List<SysPermission> list) {
        return list.stream().map(MenuTreeNodeVOs::from).toList();
    }
}
