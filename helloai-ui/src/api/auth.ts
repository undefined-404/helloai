import request from './request'

export interface LoginResponse {
  token: string
  type: 'admin' | 'agent'
  displayName?: string
  role?: string
}

export const authApi = {
  login(data: { type: 'admin' | 'agent'; username?: string; credential: string }) {
    return request.post<any, LoginResponse>('/auth/login', data)
  },
  logout() {
    return request.post('/auth/logout')
  },
  changePassword(data: { currentPassword: string; newPassword: string }) {
    return request.post('/auth/change-password', data)
  },
  me() {
    return request.get<any, LoginResponse>('/auth/me')
  }
}
