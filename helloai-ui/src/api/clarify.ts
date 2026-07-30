import request from './request'
import type { ClarifyConversationDetail, RequirementConversation, Task, LongId } from '@/types'

// V29 对话式需求澄清: 每轮走一次 LLM，耗时远超全局 30s，create/send 单请求覆盖 timeout
export const clarifyApi = {
  // 新建会话（首条用户消息触发一轮 LLM）
  create(message: string) {
    return request.post<any, ClarifyConversationDetail>(
      '/requirement-conversations', { message }, { timeout: 120_000 })
  },
  // 追加用户消息并走一轮 LLM 澄清
  send(id: LongId, message: string) {
    return request.post<any, ClarifyConversationDetail>(
      `/requirement-conversations/${id}/messages`, { message }, { timeout: 120_000 })
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
