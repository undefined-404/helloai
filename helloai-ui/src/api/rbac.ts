import request from './request'
import { paths } from './paths'
import type {
  DataRule,
  DataRuleSaveRequest,
  PageRecords,
  SysDepart,
  SysDepartSaveRequest,
  SysPermission,
  SysPermissionSaveRequest,
  SysRole,
  SysRoleSaveRequest,
  SysUserItem,
  SysUserUpdateRequest
} from '@/types'

/**
 * RBAC 管理端 API（G-012：角色/权限/用户/菜单树）。
 * 位于 /api/admin/** 下，AdminOnlyInterceptor 强制 admin 身份，
 * 细粒度权限码由后端 @SaCheckPermission 校验（role:manage / user:manage）。
 */
export const rbacApi = {
  // ── 角色 CRUD + 权限绑定 ──
  roles() {
    return request.get<any, SysRole[]>(paths.rbac.roles)
  },
  createRole(data: SysRoleSaveRequest) {
    return request.post<any, SysRole>(paths.rbac.roles, data)
  },
  updateRole(id: string | number, data: SysRoleSaveRequest) {
    return request.put<any, SysRole>(paths.rbac.roleById(id), data)
  },
  deleteRole(id: string | number) {
    return request.delete(paths.rbac.roleById(id))
  },
  rolePermissionIds(id: string | number) {
    return request.get<any, Array<string | number>>(paths.rbac.rolePermissions(id))
  },
  assignRolePermissions(id: string | number, permissionIds: Array<string | number>) {
    return request.put(paths.rbac.rolePermissions(id), { permissionIds })
  },

  // ── 权限码全量列表 ──
  permissions() {
    return request.get<any, SysPermission[]>(paths.rbac.permissions)
  },

  // ── BASE-2.1 菜单/权限管理：全量权限树 + CRUD ──
  permissionTree() {
    return request.get<any, SysPermission[]>(paths.rbac.permissionTree)
  },
  createPermission(data: SysPermissionSaveRequest) {
    return request.post<any, SysPermission>(paths.rbac.permissionCreate, data)
  },
  updatePermission(id: string | number, data: SysPermissionSaveRequest) {
    return request.put<any, SysPermission>(paths.rbac.permissionUpdate(id), data)
  },
  deletePermission(id: string | number) {
    return request.delete(paths.rbac.permissionDelete(id))
  },
  dataRule(permissionId: string | number) {
    return request.get<any, DataRule>(paths.rbac.permissionDataRule(permissionId))
  },
  saveDataRule(permissionId: string | number, data: DataRuleSaveRequest) {
    return request.put(paths.rbac.permissionDataRule(permissionId), data)
  },

  // ── 用户分页 + 角色分配 ──
  userPage(params: { page?: number; size?: number; keyword?: string; departId?: string | number }) {
    return request.get<any, PageRecords<SysUserItem>>(paths.rbac.userPage, { params })
  },
  userRoleIds(userId: string | number) {
    return request.get<any, Array<string | number>>(paths.rbac.userRoles(userId))
  },
  assignUserRoles(userId: string | number, roleIds: Array<string | number>) {
    return request.put(paths.rbac.userRoles(userId), { roleIds })
  },
  updateUser(userId: string | number, data: SysUserUpdateRequest) {
    return request.put(paths.rbac.userById(userId), data)
  },
  resetUserPassword(userId: string | number, newPassword: string) {
    return request.put(paths.rbac.userPassword(userId), { newPassword })
  },
  userDepartIds(userId: string | number) {
    return request.get<any, Array<string | number>>(paths.rbac.userDeparts(userId))
  },
  assignUserDeparts(userId: string | number, departIds: Array<string | number>) {
    return request.put(paths.rbac.userDeparts(userId), { departIds })
  },

  // ── BASE-3.2 部门（组织架构树）──
  departTree() {
    return request.get<any, SysDepart[]>(paths.rbac.departTree)
  },
  createDepart(data: SysDepartSaveRequest) {
    return request.post<any, SysDepart>(paths.rbac.departs, data)
  },
  updateDepart(id: string | number, data: SysDepartSaveRequest) {
    return request.put<any, SysDepart>(paths.rbac.departById(id), data)
  },
  deleteDepart(id: string | number) {
    return request.delete(paths.rbac.departById(id))
  },

  // ── 菜单树（RBAC 菜单 DB 化读侧）──
  menuTree() {
    return request.get<any, SysPermission[]>(paths.rbac.menuTree)
  }
}
