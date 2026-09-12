package com.helloai.api.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 菜单树节点 / 权限码投影（对齐后端 SysPermission，API 层不直接暴露 DB 实体）。
 *
 * <p>供「前端侧边栏菜单树」与「菜单/权限管理页」共用：MENU 节点携带
 * path / icon / component（驱动前端动态路由），API 节点为平铺叶子。</p>
 */
@Data
public class MenuTreeNodeVO {

    private Long id;
    private String code;
    private String name;
    private String type;
    private Integer sort;
    private Long parentId;
    private String path;
    private String icon;
    private String component;
    /** 隐藏菜单：0=显示 1=隐藏（侧边栏不显示，路由仍注册） */
    private Integer hidden;
    /** 页面缓存：0=不缓存 1=缓存 */
    private Integer keepAlive;
    /** 外链地址（非空时点击新窗口打开，不注册前端路由） */
    private String externalLink;
    private List<MenuTreeNodeVO> children;
}
