import request from './request'
import { paths } from './paths'
import type { SubTask, ChangeStatusRequest, PageResult, LongId, CreateSubTaskPayload, TaskTimelineItem, ConversationMessageItem } from '@/types'

export const subTaskApi = {
  // taskId: 按主任务过滤（任务管理页跳转携带），LongId 传 string 防精度丢
  // 传 page 时后端返回 PageResult 真分页；不传 page 返回全量数组（SKILL.md 外部 Agent 契约）
  list(params: { taskId?: LongId; status?: string; page: number; pageSize?: number }) {
    return request.get<any, PageResult<SubTask>>(paths.subTasks.list, { params })
  },
  // 按主任务拉全量子任务（不传 page 走后端全量数组契约；依赖图与序号映射用）
  listAllByTask(taskId: LongId) {
    return request.get<any, SubTask[]>(paths.subTasks.list, { params: { taskId } })
  },
  // v1.1 修复: LongID 后端已全局序列化为 string，传 string 避免任何 Number() 精度丢
  getById(id: LongId) {
    return request.get<any, SubTask>(paths.subTasks.getById(id))
  },
  // M4.5: 快速派发单建（QuickDispatchDialog 逐项调用）
  create(data: CreateSubTaskPayload) {
    return request.post<any, SubTask>(paths.subTasks.create, data)
  },
  // M4.5: 批量派发（可选加速路径，QuickDispatchDialog 默认走逐项 create）
  createBatch(data: CreateSubTaskPayload[]) {
    return request.post<any, SubTask[]>(paths.subTasks.batch, data)
  },
  // M4.5: 子任务执行时间线（SubTaskDetail 轮询使用）
  timeline(id: LongId) {
    return request.get<any, TaskTimelineItem[]>(paths.subTasks.timeline(id))
  },
  // V28: 子任务执行对话流（执行产出全文 + 核验 Prompt/分析原文）
  conversation(id: LongId) {
    return request.get<any, ConversationMessageItem[]>(paths.subTasks.conversation(id))
  },
  // V25: 死信人工兜底指派（DEAD_LETTER → ASSIGNED，熔断计数清零）
  redispatchDeadLetter(id: LongId, agentId: LongId) {
    return request.post(paths.subTasks.redispatchDeadLetter(id), { agentId })
  },
  // BLOCKED 子任务重新调度（reset → PENDING 后交调度链，dispatchBlockedSubTask）
  reassign(id: LongId, agentId: LongId) {
    return request.post(paths.subTasks.reassign(id), { agentId })
  },
  changeStatus(data: ChangeStatusRequest) {
    return request.post(paths.subTasks.changeStatus, data)
  },
  claim(id: LongId, agentId: LongId) {
    return request.post(paths.subTasks.claim(id), null, { params: { agentId } })
  },
  start(id: LongId) {
    return request.post(paths.subTasks.start(id))
  },
  submit(id: LongId) {
    return request.post(paths.subTasks.submit(id))
  },
  block(id: LongId) {
    return request.post(paths.subTasks.block(id))
  },
  mine(agentId: LongId) {
    return request.get<any, SubTask[]>(paths.subTasks.mine, { params: { agentId } })
  },
  available() {
    return request.get<any, SubTask[]>(paths.subTasks.available)
  },
  pause(id: LongId) {
    return request.post(paths.subTasks.pause(id))
  },
  resume(id: LongId) {
    return request.post(paths.subTasks.resume(id))
  }
}
