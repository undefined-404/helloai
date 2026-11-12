import request from './request'
import { paths } from './paths'
import type { Task, SubTask, ChangeStatusRequest, PageResult, LongId, CreateSubTaskPayload, TaskTimelineItem, ConversationMessageItem, Uncertainty, AcceptanceKind, RedispatchResult } from '@/types'

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
  // G-010 草案人工修订：确认前编辑技能指派与执行约束（null=不修改该字段）
  // G-011 不确定性逐条编辑同入口下发；空数组=清空，null=不修改
  // TD-2 验收判据同入口下发（acceptanceKind，null=不修改；非法值服务端降级 AUTOMATED）
  updateDraftByCode(taskId: LongId, code: string, data: {
    requiredSkills?: string[] | null
    constraints?: string | null
    uncertainties?: Uncertainty[] | null
    acceptanceKind?: AcceptanceKind | null
  }) {
    return request.put(paths.tasks.updateDraftByCode(taskId, code), data)
  },
  // ADR-008 §5 草案派生：一条草案 → 独立任务 Y（幂等；草案本身保持 ACTIVE）
  deriveDraftByCode(taskId: LongId, code: string) {
    return request.post<any, Task>(paths.tasks.deriveDraftByCode(taskId, code))
  },
  // ADR-008 §5 对话版：草案「拆出去」先开会话问清执行前提（仓库/运行时/数据源），
  // 终稿确认时才建派生任务 Y；返回 conversationId
  startDraftDeriveSession(taskId: LongId, code: string, plannerAgentId?: LongId) {
    return request.post<any, LongId>(paths.tasks.draftDeriveSession(taskId, code), null, {
      params: plannerAgentId ? { plannerAgentId } : {}
    })
  },
  regenerateDraftByCode(taskId: LongId, code: string) {
    return request.post<any, SubTask>(paths.tasks.regenerateDraftByCode(taskId, code))
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
  // 契约：被闸门拦截时返回 { applied:false, reason, nextAllowed }（任务状态不变）
  reassign(id: LongId, agentId: LongId) {
    return request.post<any, RedispatchResult>(paths.subTasks.reassign(id), { agentId })
  },
  // ASSIGNED / IN_PROGRESS / PAUSED 人工换人（先标 BLOCKED 再重新调度，redispatchInProgress）
  // 契约：被闸门拦截时返回 { applied:false, reason, nextAllowed }（任务状态不变）
  redispatchInProgress(id: LongId, agentId: LongId) {
    return request.post<any, RedispatchResult>(paths.subTasks.redispatchInProgress(id), { agentId })
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
  },
  // ADR-008 §12 执行期重构：BLOCKED / DEAD_LETTER / REWORK / PAUSED → RESTRUCTURING
  enterRestructuring(id: LongId, reason?: string) {
    return request.post(paths.subTasks.enterRestructuring(id), null, {
      params: reason ? { reason } : {},
    })
  },
  // 执行期编辑四要素（仅 RESTRUCTURING；改动前值进 context.restructureHistory）
  updateRestructure(id: LongId, data: {
    title?: string | null
    content?: string | null
    deliverable?: string | null
    acceptance?: string | null
    constraints?: string | null
    acceptanceKind?: AcceptanceKind | null
  }) {
    return request.post<any, SubTask>(paths.subTasks.updateRestructure(id), data)
  },
  // 整改完成回派发（RESTRUCTURING → PENDING）：后端先过 G5，未通过会返回业务错误
  applyRestructure(id: LongId) {
    return request.post<any, SubTask>(paths.subTasks.applyRestructure(id))
  },
  // 执行期「拆出去当主任务」：开 ACTIVE 派生会话（幂等），返回 conversationId
  startRestructureSession(id: LongId, plannerAgentId?: LongId) {
    return request.post<any, LongId>(paths.subTasks.restructureSession(id), null, {
      params: plannerAgentId ? { plannerAgentId } : {},
    })
  }
}
