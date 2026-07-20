import request from './request'
import type { Task } from '@/types'

export const taskApi = {
  list(params?: { status?: string }) {
    return request.get<any, Task[]>('/tasks', { params })
  },
  getById(id: number) {
    return request.get<any, Task>(`/tasks/${id}`)
  },
  // M4.5: 派发控制台新建任务入口
  create(data: { title: string; description?: string }) {
    return request.post<any, Task>('/tasks', data)
  }
}
