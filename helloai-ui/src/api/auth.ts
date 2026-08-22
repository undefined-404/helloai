import request from './request'
import { paths } from './paths'

export interface LoginResponse {
  token: string
  type: 'admin' | 'agent'
  displayName?: string
  role?: string
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
  me() {
    return request.get<any, LoginResponse>(paths.auth.me)
  }
}
