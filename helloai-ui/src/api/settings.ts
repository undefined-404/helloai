import request from './request'

export const settingsApi = {
  getStatus() {
    return request.get<any, { setupFinished: boolean; hasUsers: boolean; userCount: number }>('/setup/getStatus')
  },
  initialize(data: { adminPassword: string; systemName: string; systemDescription?: string; adminUsername?: string }) {
    return request.post('/setup/initialize', data)
  },
  getConfig() {
    return request.get<any, Record<string, string>>('/admin/config')
  },
  getConfigValue(key: string) {
    return request.get<any, string>(`/admin/config/getByKey/${key}`)
  },
  updateConfig(key: string, value: string) {
    return request.put(`/admin/config/updateByKey/${key}`, { value })
  },
  batchUpdateConfig(map: Record<string, string>) {
    return request.put('/admin/config/batch', map)
  }
}
