package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 系统权限码（RBAC）。
 *
 * <p>权限码是「前端菜单过滤」与「后端 @SaCheckPermission」的共享事实源：
 * {@code type=MENU} 供前端按码过滤菜单显隐，{@code type=API} 供后端接口鉴权。
 * 菜单树即 DB 化结构（V78 起）：{@code parent_id/path/icon} 三列承载层级与前端路由信息，
 * 不再由前端路由硬编码菜单结构。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermission extends BaseEntity {

    private String code;
    private String name;
    private String type;
    private Integer sort;
    /** 父菜单 ID（type=MENU 时有效，NULL=一级菜单） */
    private Long parentId;
    /** 前端路由路径（type=MENU 时有效） */
    private String path;
    /** 前端菜单图标组件名（对齐 @element-plus/icons-vue） */
    private String icon;
    /** 前端组件路径（相对 src/views，不含扩展名；type=MENU 时有效，驱动动态路由懒加载） */
    private String component;
    /** 隐藏菜单：0=显示（默认）1=隐藏（不在侧边栏显示，路由仍注册可达） */
    private Integer hidden;
    /** 页面缓存：0=不缓存（默认）1=缓存（前端 keep-alive 保留状态） */
    private Integer keepAlive;
    /** 外链地址（非空时点击新窗口打开，不注册前端路由） */
    private String externalLink;
    /** 是否配置数据权限：0=未配置（默认，不过滤）1=已配置（按 sys_permission_data_rule 过滤） */
    private Integer ruleFlag;

    /** 子菜单（仅菜单树接口返回时填充，非表字段） */
    @TableField(exist = false)
    private List<SysPermission> children;
}