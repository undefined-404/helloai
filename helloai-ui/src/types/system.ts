// ============================================================
// RBAC 系统管理类型 — 对齐后端 com.helloai.core.system 实体 / DTO
// 覆盖：权限码 / 菜单树 / 角色 / 用户管理列表
// ============================================================

import type { LongId } from './common'

/** 系统权限码 / 菜单树节点（对齐后端 SysPermission） */
export interface SysPermission {
  id: LongId
  code: string
  name: string
  type: string
  sort?: number
  /** 父菜单 ID（NULL=一级菜单，type=MENU 时有效） */
  parentId?: LongId | null
  /** 前端路由路径（type=MENU 时有效） */
  path?: string | null
  /** 前端菜单图标组件名（对齐 @element-plus/icons-vue） */
  icon?: string | null
  /** 前端组件路径（相对 src/views，不含扩展名；type=MENU 时有效，驱动动态路由懒加载） */
  component?: string | null
  /** 隐藏菜单：0=显示 1=隐藏（侧边栏不显示，路由仍注册） */
  hidden?: number | null
  /** 页面缓存：0=不缓存 1=缓存 */
  keepAlive?: number | null
  /** 外链地址（非空时点击新窗口打开，不注册前端路由） */
  externalLink?: string | null
  /** 子菜单（仅菜单树接口返回时填充） */
  children?: SysPermission[]
}

/** 系统角色（对齐后端 SysRole） */
export interface SysRole {
  id: LongId
  code: string
  name: string
  description?: string | null
  status: string
  sort?: number
}

/** 用户管理列表项（对齐后端 SysUserItem） */
export interface SysUserItem {
  id: LongId
  username: string
  nickname?: string | null
  email?: string | null
  phone?: string | null
  status: string
  /** 存量 sys_user.role 单字段（兼容展示，新分配以关联表 sys_user_role 为准） */
  role?: string | null
  lastLoginTime?: string | null
  lastLoginIp?: string | null
  /** 已分配角色码列表（如 [SUPER_ADMIN] / [ADMIN]） */
  roleCodes?: string[]
  /** 已关联部门（BASE-3.2） */
  departIds?: LongId[]
  departNames?: string[]
}

/** 系统部门（组织架构树，对齐后端 SysDepart） */
export interface SysDepart {
  id: LongId
  parentId?: LongId | null
  name: string
  sort?: number
  status: string
  description?: string | null
  children?: SysDepart[]
}

/** 部门创建/更新请求 */
export interface SysDepartSaveRequest {
  name: string
  parentId?: LongId | null
  sort?: number
  status?: string
  description?: string | null
}

/** 权限数据规则（BASE-3.3，受控枚举） */
export interface DataRule {
  permissionId: LongId
  /** ALL / DEPT / DEPT_AND_CHILD / CUSTOM */
  ruleType: string
  /** CUSTOM 时的部门 ID 逗号分隔 */
  ruleValue?: string | null
}

/** 权限数据规则保存请求 */
export interface DataRuleSaveRequest {
  ruleType: string
  ruleValue?: string | null
}

/** 后端 MyBatis-Plus IPage 序列化结果 */
export interface PageRecords<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

/** 角色保存/更新请求（对齐后端 SysRoleSaveRequest） */
export interface SysRoleSaveRequest {
  code?: string
  name: string
  description?: string | null
  status: string
  sort?: number
}

/** 权限码/菜单 创建·更新请求（对齐后端 SysPermissionSaveRequest） */
export interface SysPermissionSaveRequest {
  code: string
  name: string
  type: string
  sort?: number
  parentId?: LongId | null
  path?: string | null
  icon?: string | null
  component?: string | null
  hidden?: number | null
  keepAlive?: number | null
  externalLink?: string | null
}

/** 用户信息更新请求（字段为 null 时不更新，对齐后端 SysUserUpdateRequest） */
export interface SysUserUpdateRequest {
  nickname?: string | null
  email?: string | null
  phone?: string | null
  status?: string | null
}
