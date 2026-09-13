import request from './request'
import { paths } from './paths'

export interface LoginResponse {
  token: string
  type: 'admin' | 'agent'
  displayName?: string
  permissions?: string[]
  roles?: string[]
}

export const authApi = {
  login(data: { type: 'admin' | 'agent'; username?: string; credential: string }) {
    return request.post<any, LoginResponse>(paths.auth.login, data)
  },
  logout() {
    return request.post(paths.auth.logout)
  },
  changePassword(data: { currentPassword: string; newPassword: string }) {
    return request.post(paths.auth.changePassword, data)
  },
  /** 自助注册（BASE-4.5；是否开放由后端 sys_config.auth.register.enabled 门控，注册账号默认 GUEST 只读） */
  register(data: { username: string; password: string; nickname?: string }) {
    return request.post<any, void>(paths.auth.register, data)
  },
  me() {
    return request.get<any, LoginResponse>(paths.auth.me)
  }
}
