import request from './request'
import type { AxiosResponse } from 'axios'
import type { Task, TaskRelatedCounts, TaskFinalReport, SubTask, LongId } from '@/types'

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
  },
  // V26 触发 AI 拆解: 仅 PENDING 可调，成功后 Task 停 PLANNING、草案落库 PENDING_PLAN_REVIEW
  // LLM 拆解耗时远超全局 30s，单请求覆盖 timeout
  plan(id: LongId) {
    return request.post<any, SubTask[]>(`/tasks/${id}/plan`, undefined, { timeout: 120_000 })
  },
  // 查看待审阅草案列表
  planDrafts(id: LongId) {
    return request.get<any, SubTask[]>(`/tasks/${id}/plan`)
  },
  // 确认草案: 草案转 PENDING + Task→IN_PROGRESS，按配置自动分发
  confirmPlan(id: LongId) {
    return request.post<any, SubTask[]>(`/tasks/${id}/plan/confirm`)
  },
  // 拒绝草案: 草案翻 CANCELLED，Task 回 PENDING 可重拆
  rejectPlan(id: LongId) {
    return request.post<any, { taskId: string; cancelledCount: number }>(`/tasks/${id}/plan/reject`)
  },
  // 交付物 zip 下载: 实时聚合子任务产出（概览 + 拓扑序产出文件），
  // blob 响应由拦截器放行返回完整 response 供解析文件名
  downloadDeliverables(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(`/tasks/${id}/deliverables/download`, {
      responseType: 'blob',
      timeout: 120_000
    })
  },
  // V32 最终整合报告: 读取 task.final_report 专列（content=null 表示尚未生成）
  getFinalReport(id: LongId) {
    return request.get<any, TaskFinalReport>(`/tasks/${id}/final-report`)
  },
  // 生成/重新生成整合报告: Planner 整合全部 DONE 子任务产出，仅 DONE 任务可调；
  // 后端 LLM 读超时 180s（provider read-timeout-ms），前端 240s 留出降档重试与传输余量
  generateFinalReport(id: LongId) {
    return request.post<any, TaskFinalReport>(`/tasks/${id}/final-report`, undefined, { timeout: 240_000 })
  }
}
