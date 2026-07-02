import request from './request'
import type { Task } from '@/types'

export const taskApi = {
  list(params?: { status?: string }) {
    return request.get<any, Task[]>('/tasks', { params })
  },
  getById(id: number) {
    return request.get<any, Task>(`/tasks/${id}`)
  }
}
