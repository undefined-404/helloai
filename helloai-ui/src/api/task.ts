import request from './request'
import type { AxiosResponse } from 'axios'
import type { Task, TaskAgentPolicy, TaskRelatedCounts, TaskFinalReport, TaskIteration, SubTask, LongId } from '@/types'

// A1: 任务创建/编辑公共载荷（SLA + V47 执行策略/技能；update 时 null 字段后端不更新、空集合=清空）
export interface TaskFormPayload {
  title: string
  description?: string
  slaMinutes?: number | null
  agentPolicy?: TaskAgentPolicy | null
  requiredSkills?: string[]
}

export const taskApi = {
  list(params?: { status?: string }) {
    return request.get<any, Task[]>('/tasks/list', { params })
  },
  // v1.1 修复: LongID 后端已全局序列化为 string，传 string 避免任何 Number() 精度丢
  getById(id: LongId) {
    return request.get<any, Task>(`/tasks/getById/${id}`)
  },
  // M4.5: 派发控制台新建任务入口（A1: 支持 SLA/执行策略/技能透传）
  create(data: TaskFormPayload) {
    return request.post<any, Task>('/tasks', data)
  },
  // 任务管理 CRUD: 编辑基本信息（A1: 扩展 SLA/执行策略/技能）
  update(id: LongId, data: TaskFormPayload) {
    return request.put<any, Task>(`/tasks/updateById/${id}`, data)
  },
  // 重新发布: 重置 PENDING + 重新通知全部 PLANNER（DONE 不允许）
  republish(id: LongId) {
    return request.post<any, Task>(`/tasks/republishById/${id}`)
  },
  // 删除前关联数据统计（风险提示）
  relatedCounts(id: LongId) {
    return request.get<any, TaskRelatedCounts>(`/tasks/listRelatedCountsByTaskId/${id}`)
  },
  // 级联删除: 需输入任务标题确认
  deleteTask(id: LongId, confirmTitle: string) {
    return request.delete<any, TaskRelatedCounts>(`/tasks/deleteById/${id}`, { data: { confirmTitle } })
  },
  // V26 触发 AI 拆解: 仅 PENDING 可调，成功后 Task 停 PLANNING、草案落库 PENDING_PLAN_REVIEW
  // LLM 拆解耗时远超全局 30s，单请求覆盖 timeout
  plan(id: LongId) {
    return request.post<any, SubTask[]>(`/tasks/planById/${id}`, undefined, { timeout: 120_000 })
  },
  // 查看待审阅草案列表
  planDrafts(id: LongId) {
    return request.get<any, SubTask[]>(`/tasks/findPlanByTaskId/${id}`)
  },
  // 确认草案: 草案转 PENDING + Task→IN_PROGRESS，按配置自动分发
  confirmPlan(id: LongId) {
    return request.post<any, SubTask[]>(`/tasks/confirmPlanByTaskId/${id}`)
  },
  // 拒绝草案: 草案翻 CANCELLED，Task 回 PENDING 可重拆
  rejectPlan(id: LongId) {
    return request.post<any, { taskId: string; cancelledCount: number }>(`/tasks/rejectPlanByTaskId/${id}`)
  },
  // 交付物 zip 下载: 实时聚合子任务产出（概览 + 拓扑序产出文件），
  // blob 响应由拦截器放行返回完整 response 供解析文件名
  downloadDeliverables(id: LongId) {
    return request.get<any, AxiosResponse<Blob>>(`/tasks/downloadDeliverablesByTaskId/${id}`, {
      responseType: 'blob',
      timeout: 120_000
    })
  },
  // V32 最终整合报告: 读取 task.final_report 专列（content=null 表示尚未生成）
  getFinalReport(id: LongId) {
    return request.get<any, TaskFinalReport>(`/tasks/findFinalReportByTaskId/${id}`)
  },
  // 生成/重新生成整合报告: Planner 整合全部 DONE 子任务产出，仅 DONE 任务可调；
  // 后端 LLM 读超时 180s（provider read-timeout-ms），前端 240s 留出降档重试与传输余量
  generateFinalReport(id: LongId) {
    return request.post<any, TaskFinalReport>(`/tasks/generateFinalReportByTaskId/${id}`, undefined, { timeout: 240_000 })
  },
  // V42 任务执行迭代记录
  findTaskIterationsByTaskId(id: LongId) {
    return request.get<any, TaskIteration[]>(`/tasks/findTaskIterationsByTaskId/${id}`)
  },
  // V42 触发历史迭代记录回填（一次性，幂等）
  backfillTaskIterations() {
    return request.post<any, { backfilledCount: number }>('/tasks/backfillTaskIterations')
  }
}
