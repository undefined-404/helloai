import request from './request'
import type { ClarifyConversationDetail, ClarifySelection, PlannerOption, RequirementConversation, Task, LongId } from '@/types'

// V29 对话式需求澄清: 每轮走一次 LLM，耗时远超全局 30s，create/send/retry 单请求覆盖 timeout
export const clarifyApi = {
  // 新建会话（首条用户消息触发一轮 LLM；plannerAgentId 空=系统自动选择）
  create(message: string, plannerAgentId?: LongId | null) {
    return request.post<any, ClarifyConversationDetail>(
      '/requirement-conversations',
      { message, plannerAgentId: plannerAgentId ?? null },
      { timeout: 120_000 })
  },
  // 追加用户消息并走一轮 LLM 澄清（可附 V33 结构化选项回答快照）
  send(id: LongId, message: string, selectedOptions?: ClarifySelection[] | null) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/${id}/messages`,
      { message, selectedOptions: selectedOptions?.length ? selectedOptions : null },
      { timeout: 120_000 })
  },
  // 重试上一轮 LLM（仅当最后一条是用户消息，即上轮 LLM 失败时可用）
  retry(id: LongId) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/${id}/retry`, null, { timeout: 120_000 })
  },
  // Planner 下拉选数据源（平台内 PLANNER 可选 + 在班外部 Agent 置灰）
  plannerOptions() {
    return request.get<any, PlannerOption[]>('/requirement-conversations/planner-options')
  },
  // 会话列表（按创建时间倒序，LIMIT 50）
  list() {
    return request.get<any, RequirementConversation[]>('/requirement-conversations')
  },
  // 会话详情（含全部消息按 seq 升序）
  detail(id: LongId) {
    return request.get<any, ClarifyConversationDetail>(`/requirement-conversations/${id}`)
  },
  // 终稿确认: 创建任务（PENDING）并回填会话 FINALIZED
  finalize(id: LongId) {
    return request.post<any, Task>(`/requirement-conversations/${id}/finalize`)
  },
  // 重新生成: FINALIZED 会话原任务已删除时，复用终稿重建任务
  regenerate(id: LongId) {
    return request.post<any, Task>(`/requirement-conversations/${id}/regenerate`)
  },
  // 放弃会话: ACTIVE → ABANDONED
  abandon(id: LongId) {
    return request.post<any, void>(`/requirement-conversations/${id}/abandon`)
  }
}
