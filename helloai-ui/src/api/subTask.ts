import request from './request'
import type { SubTask, ChangeStatusRequest, PageResult } from '@/types'

export const subTaskApi = {
  list(params?: { status?: string; page?: number; size?: number }) {
    return request.get<any, SubTask[]>('/sub-tasks', { params })
  },
  getById(id: number) {
    return request.get<any, SubTask>(`/sub-tasks/${id}`)
  },
  changeStatus(data: ChangeStatusRequest) {
    return request.post('/sub-tasks/change-status', data)
  },
  claim(id: number, agentId: number) {
    return request.post(`/sub-tasks/${id}/claim`, null, { params: { agentId } })
  },
  start(id: number) {
    return request.post(`/sub-tasks/${id}/start`)
  },
  submit(id: number) {
    return request.post(`/sub-tasks/${id}/submit`)
  },
  block(id: number) {
    return request.post(`/sub-tasks/${id}/block`)
  },
  mine(agentId: number) {
    return request.get<any, SubTask[]>('/sub-tasks/mine', { params: { agentId } })
  },
  available() {
    return request.get<any, SubTask[]>('/sub-tasks/available')
  },
  pause(id: number) {
    return request.post(`/sub-tasks/${id}/pause`)
  },
  resume(id: number) {
    return request.post(`/sub-tasks/${id}/resume`)
  }
}
