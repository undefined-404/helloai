package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 系统部门（组织架构树，RBAC，BASE-3.2）。
 *
 * <p>{@code parent_id} 承载层级（NULL=顶级部门），{@code children} 为树接口返回时填充的非表字段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_depart")
public class SysDepart extends BaseEntity {

    /** 父部门 ID（NULL=顶级部门） */
    private Long parentId;
    private String name;
    private Integer sort;
    /** 状态：ACTIVE / DISABLED */
    private String status;
    private String description;

    /** 子部门（仅树接口返回时填充，非表字段） */
    @TableField(exist = false)
    private List<SysDepart> children;
}
