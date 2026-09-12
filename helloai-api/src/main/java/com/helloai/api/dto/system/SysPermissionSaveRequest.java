package com.helloai.api.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 权限码 / 菜单 创建·更新请求（BASE-2.1）。
 *
 * <p>type 仅支持 MENU / API；MENU 可带 parentId / path / icon / component，
 * API 无层级字段。更新时 code 不参与（取路径 id），服务层仅按非 null 字段更新。</p>
 */
@Data
public class SysPermissionSaveRequest {

    @NotBlank(message = "权限码不能为空")
    private String code;

    @NotBlank(message = "权限名称不能为空")
    private String name;

    @NotBlank(message = "权限类型不能为空")
    private String type;

    private Integer sort;

    /** 父菜单 ID（type=MENU 时有效，NULL=一级菜单） */
    private Long parentId;

    /** 前端路由路径（type=MENU 时有效） */
    private String path;

    /** 前端菜单图标组件名（对齐 @element-plus/icons-vue） */
    private String icon;

    /** 前端组件路径（相对 src/views，type=MENU 时有效） */
    private String component;

    /** 隐藏菜单：0=显示（默认）1=隐藏（侧边栏不显示，路由仍注册） */
    private Integer hidden;

    /** 页面缓存：0=不缓存（默认）1=缓存 */
    private Integer keepAlive;

    /** 外链地址（非空时点击新窗口打开，不注册前端路由） */
    private String externalLink;
}
