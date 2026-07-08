import request from './request'
import type { SubTask, ChangeStatusRequest, PageResult, LongId } from '@/types'

export const subTaskApi = {
  list(params?: { status?: string; page?: number; size?: number }) {
    return request.get<any, SubTask[]>('/sub-tasks', { params })
  },
  // v1.1 修复: LongID 后端已全局序列化为 string，传 string 避免任何 Number() 精度丢
  getById(id: LongId) {
    return request.get<any, SubTask>(`/sub-tasks/${id}`)
  },
  changeStatus(data: ChangeStatusRequest) {
    return request.post('/sub-tasks/change-status', data)
  },
  claim(id: LongId, agentId: LongId) {
    return request.post(`/sub-tasks/${id}/claim`, null, { params: { agentId } })
  },
  start(id: LongId) {
    return request.post(`/sub-tasks/${id}/start`)
  },
  submit(id: LongId) {
    return request.post(`/sub-tasks/${id}/submit`)
  },
  block(id: LongId) {
    return request.post(`/sub-tasks/${id}/block`)
  },
  mine(agentId: LongId) {
    return request.get<any, SubTask[]>('/sub-tasks/mine', { params: { agentId } })
  },
  available() {
    return request.get<any, SubTask[]>('/sub-tasks/available')
  },
  pause(id: LongId) {
    return request.post(`/sub-tasks/${id}/pause`)
  },
  resume(id: LongId) {
    return request.post(`/sub-tasks/${id}/resume`)
  }
}
