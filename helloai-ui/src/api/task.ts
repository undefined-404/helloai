import request from './request'
import type { Task, TaskRelatedCounts, LongId } from '@/types'

export const taskApi = {
  list(params?: { status?: string }) {
    return request.get<any, Task[]>('/tasks', { params })
  },
  // v1.1 修复: LongID 后端已全局序列化为 string，传 string 避免任何 Number() 精度丢
  getById(id: LongId) {
    return request.get<any, Task>(`/tasks/${id}`)
  },
  // M4.5: 派发控制台新建任务入口
  create(data: { title: string; description?: string }) {
    return request.post<any, Task>('/tasks', data)
  },
  // 任务管理 CRUD: 编辑基本信息
  update(id: LongId, data: { title: string; description?: string }) {
    return request.put<any, Task>(`/tasks/${id}`, data)
  },
  // 重新发布: 重置 PENDING + 重新通知全部 PLANNER（DONE 不允许）
  republish(id: LongId) {
    return request.post<any, Task>(`/tasks/${id}/republish`)
  },
  // 删除前关联数据统计（风险提示）
  relatedCounts(id: LongId) {
    return request.get<any, TaskRelatedCounts>(`/tasks/${id}/related-counts`)
  },
  // 级联删除: 需输入任务标题确认
  deleteTask(id: LongId, confirmTitle: string) {
    return request.delete<any, TaskRelatedCounts>(`/tasks/${id}`, { data: { confirmTitle } })
  }
}
